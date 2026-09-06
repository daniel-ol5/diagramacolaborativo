package com.modelcollab.diagram.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.service.CanonicalMutationApplier;
import com.modelcollab.collaboration.service.MutacionRechazadaException;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.diagram.dto.OfflineOperation;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.ReconciliationReport;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.model.DiagramOperation;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebase de las operaciones que un cliente encoló mientras estaba sin conexión (§9.2).
 *
 * <h2>Por qué acá se aplican operaciones "que requieren lock" sin lock</h2>
 *
 * <p>Leído sin contexto parece un agujero de seguridad; es exactamente lo contrario. §8.4 lo
 * define: un cliente sin conexión <b>no puede adquirir locks</b> porque el árbitro es el
 * servidor, así que en modo offline omite la fase de adquisición y edita de forma optimista
 * sobre su copia local. La exclusión mutua no desaparece — <b>se traslada del momento de la
 * edición al momento del rebase</b>. El lock es prevención y necesita árbitro en línea; el
 * rebase es detección y resolución, y funciona sin él. El sistema usa prevención cuando puede y
 * resolución cuando no puede.
 *
 * <p>Por eso este servicio no consulta el {@code LockManagerService}: las reglas de §9.2 son las
 * que hacen de exclusión mutua acá.
 *
 * <h2>El timestamp del cliente no es autoritativo</h2>
 *
 * <p>§9.2 punto 1. El {@code clientTimestamp} sólo preserva el orden relativo de la cola; el
 * sello oficial lo pone el servidor al recibir. Si se respetara el del cliente, alguien con el
 * reloj adelantado podría insertar operaciones "antes" de cambios ajenos ya aplicados y
 * reescribir la historia de los demás.
 */
@Service
public class OfflineReconciliationService {

    private static final Logger log =
            LoggerFactory.getLogger(OfflineReconciliationService.class);

    private final DiagramRepository diagramRepository;
    private final DiagramOperationRepository operationRepository;
    private final UserRepository userRepository;
    private final CanonicalMutationApplier applier;
    private final SequenceService sequenceService;
    private final ObjectMapper objectMapper;

    public OfflineReconciliationService(
            DiagramRepository diagramRepository,
            DiagramOperationRepository operationRepository,
            UserRepository userRepository,
            CanonicalMutationApplier applier,
            SequenceService sequenceService,
            ObjectMapper objectMapper) {
        this.diagramRepository = diagramRepository;
        this.operationRepository = operationRepository;
        this.userRepository = userRepository;
        this.applier = applier;
        this.sequenceService = sequenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Una operación que sobrevivió al filtro y hay que persistir. {@code payloadParaLaBitacora}
     * puede diferir del payload original: a los borrados se les agrega el nombre de lo que se
     * borró, capturado del estado anterior, que es el único momento en que ese dato existe.
     */
    private record Aceptada(
            OfflineOperation origen, OperationType tipo, Map<String, Object> payloadParaLaBitacora) {}

    @Transactional
    public ReconciliationReport reconciliar(
            UUID diagramId, UUID userId, OfflineSyncRequest peticion) {

        Diagram diagrama = diagramRepository.findById(diagramId)
                .orElseThrow(() -> MutacionRechazadaException.objetivoInexistente(
                        "El diagrama", diagramId.toString()));

        CanonicalModel estado = leer(diagrama.getCurrentState());

        // El resolutor de nombres se construye una sola vez y sólo si algo se descarta: en el
        // camino feliz, que es la mayoría de las reconciliaciones, no se toca la bitácora. Se
        // arma sobre el estado DE PARTIDA, que es donde están los elementos que el usuario creía
        // tener cuando encoló sus operaciones.
        CanonicalModel estadoDePartida = estado;
        ResolutorDeNombres[] cache = {null};
        Supplier<ResolutorDeNombres> nombres = () -> {
            if (cache[0] == null) {
                cache[0] = construirResolutor(diagramId, estadoDePartida);
            }
            return cache[0];
        };

        List<Aceptada> aceptadas = new ArrayList<>();
        List<String> descartes = new ArrayList<>();
        int aplicadas = 0;

        for (OfflineOperation operacion : peticion.operations()) {
            Optional<OperationType> tipo = OperationType.desde(operacion.op());
            if (tipo.isEmpty() || !tipo.get().implementada()) {
                descartes.add(descripcion(operacion, nombres.get())
                        + " descartado: el servidor no soporta esa operación.");
                continue;
            }
            OperationType op = tipo.get();

            // Idempotencia: si el lote ya se envió y se perdió la respuesta, el cliente reintenta
            // entero. Estas operaciones YA están en el diagrama, así que cuentan como aplicadas
            // —desde el punto de vista del usuario su cambio está— pero no se vuelven a aplicar.
            if (operationRepository
                    .findByDiagramIdAndClientMutationId(diagramId, operacion.clientMutationId())
                    .isPresent()) {
                aplicadas++;
                continue;
            }

            String motivo = motivoDeDescarte(op, operacion, estado, nombres);
            if (motivo != null) {
                descartes.add(descripcion(operacion, nombres.get()) + " descartado: " + motivo);
                continue;
            }

            try {
                Map<String, Object> paraLaBitacora = payloadParaLaBitacora(op, operacion, estado);
                estado = applier.apply(estado, op, operacion.targetId(), operacion.payload());
                aceptadas.add(new Aceptada(operacion, op, paraLaBitacora));
                aplicadas++;
            } catch (MutacionRechazadaException e) {
                descartes.add(descripcion(operacion, nombres.get()) + " descartado: "
                        + e.getMessage() + ".");
            }
        }

        if (!aceptadas.isEmpty()) {
            estado = persistir(diagrama, estado, aceptadas, userId);
        }

        log.debug("Rebase del diagrama {}: {} aplicadas, {} descartadas",
                diagramId, aplicadas, descartes.size());

        return new ReconciliationReport(aplicadas, descartes.size(), List.copyOf(descartes), estado);
    }

    // --------------------------------------------------- políticas de §9.2

    /** {@code null} si la operación se puede aplicar; si no, la frase que explica el descarte. */
    private String motivoDeDescarte(
            OperationType op,
            OfflineOperation operacion,
            CanonicalModel estado,
            Supplier<ResolutorDeNombres> nombres) {

        String targetId = operacion.targetId();
        Map<String, Object> payload =
                operacion.payload() == null ? Map.of() : operacion.payload();

        return switch (op) {
            // DELETE_* gana siempre (§9.2). Lo único que lo detiene es que ya no haya nada que
            // borrar, y eso no es un conflicto sino la misma intención cumplida por otro.
            case DELETE_CLASS, DELETE_ATTRIBUTE, DELETE_METHOD,
                    DELETE_RELATIONSHIP, DELETE_PACKAGE ->
                    existe(estado, targetId)
                            ? null
                            : "ya había sido eliminado por otro colaborador.";

            // Altas: se valida colisión de identificadores.
            case ADD_CLASS, ADD_RELATIONSHIP, ADD_PACKAGE ->
                    existe(estado, texto(payload, "id"))
                            ? "ya existe un elemento con ese identificador."
                            : null;

            // Sólo si la clase contenedora sigue existiendo: es el caso del ejemplo de §9.2,
            // que exige nombrar la clase —"la clase 'Cliente' fue eliminada"— y no describirla.
            // El nombre sale de la bitácora, porque en el estado actual la clase ya no está.
            case ADD_ATTRIBUTE, ADD_METHOD ->
                    existeClase(estado, targetId)
                            ? null
                            : "la clase " + nombres.get().de(targetId)
                                    + " fue eliminada por otro colaborador.";

            // El resto modifica algo existente; si el objetivo se borró durante la desconexión,
            // la operación pierde sentido y se descarta.
            default ->
                    existe(estado, targetId)
                            ? null
                            : "el elemento fue eliminado por otro colaborador durante la "
                                    + "desconexión.";
        };
    }

    private static boolean existeClase(CanonicalModel estado, String id) {
        return id != null && estado.classes().stream().anyMatch(c -> id.equals(c.id()));
    }

    private static boolean existe(CanonicalModel estado, String id) {
        if (id == null) {
            return false;
        }
        return existeClase(estado, id)
                || estado.relationships().stream().anyMatch(r -> id.equals(r.id()))
                || estado.packages().stream().anyMatch(p -> id.equals(p.id()))
                || estado.classes().stream().anyMatch(c ->
                        c.attributes().stream().anyMatch(a -> id.equals(a.id()))
                                || c.methods().stream().anyMatch(m -> id.equals(m.id())));
    }

    // ------------------------------------------------- mensajes para el usuario

    /**
     * Arma la parte izquierda del mensaje: {@code ADD_ATTRIBUTE 'telefono'}. El nombre sale del
     * payload cuando la operación lo trae (una alta) y del resolutor cuando no (una baja o una
     * modificación, donde el elemento puede haber desaparecido del estado actual).
     */
    private String descripcion(OfflineOperation operacion, ResolutorDeNombres nombres) {
        Map<String, Object> payload =
                operacion.payload() == null ? Map.of() : operacion.payload();
        String delPayload = texto(payload, "name");
        if (delPayload != null) {
            return operacion.op() + " '" + delPayload + "'";
        }
        // Una operación contra la raíz del diagrama no tiene a quién nombrar; se deja el op solo
        // en vez de rellenar con un marcador que no le dice nada al usuario.
        return operacion.targetId() == null
                ? operacion.op()
                : operacion.op() + " " + nombres.de(operacion.targetId());
    }

    private ResolutorDeNombres construirResolutor(UUID diagramId, CanonicalModel estado) {
        return new ResolutorDeNombres(
                estado,
                operationRepository.findByDiagramIdOrderBySequenceNumAsc(diagramId),
                objectMapper);
    }

    // ------------------------------------------------------------ persistencia

    private CanonicalModel persistir(
            Diagram diagrama, CanonicalModel estado, List<Aceptada> aceptadas, UUID userId) {

        // Un solo UPDATE reserva todo el bloque: pedir los números de a uno serían N viajes a la
        // base para el mismo resultado, y el bloque queda garantizado consecutivo.
        long ultima = sequenceService.reservar(diagrama.getId(), aceptadas.size());
        long primera = ultima - aceptadas.size() + 1;

        var autor = userRepository.getReferenceById(userId);
        long secuencia = primera;
        for (Aceptada aceptada : aceptadas) {
            operationRepository.save(new DiagramOperation(
                    diagrama,
                    autor,
                    aceptada.tipo().name(),
                    aceptada.origen().targetId(),
                    escribirPayload(aceptada.payloadParaLaBitacora()),
                    aceptada.origen().clientMutationId(),
                    secuencia++));
        }

        CanonicalModel versionado = new CanonicalModel(
                estado.schemaVersion(), ultima,
                estado.packages(), estado.classes(), estado.relationships());

        diagrama.setCurrentState(escribir(versionado));
        diagrama.setMutationVersion(ultima);
        return versionado;
    }

    // ------------------------------------------------------------- utilitarios

    /**
     * Payload tal como se guarda en la bitácora. A los borrados se les agrega el nombre del
     * elemento tomado del estado <b>previo</b>: después de aplicar la operación ese nombre no
     * existe en ningún lado, y es justo lo que hace falta para que un rebase futuro pueda decir
     * "la clase 'Cliente' fue eliminada" en vez de escupir un UUID.
     */
    private Map<String, Object> payloadParaLaBitacora(
            OperationType op, OfflineOperation operacion, CanonicalModel estadoPrevio) {
        Map<String, Object> payload = operacion.payload() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(operacion.payload());

        if (op.name().startsWith("DELETE_") && !payload.containsKey("name")) {
            CanonicalMutationApplier.nombreDe(estadoPrevio, operacion.targetId())
                    .ifPresent(nombre -> payload.put("name", nombre));
        }
        return payload;
    }

    private static String texto(Map<String, Object> payload, String campo) {
        return payload.get(campo) instanceof String s && !s.isBlank() ? s : null;
    }

    private CanonicalModel leer(String json) {
        try {
            return objectMapper.readValue(json, CanonicalModel.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "El current_state almacenado no es un modelo canónico válido", e);
        }
    }

    private String escribir(CanonicalModel modelo) {
        try {
            return objectMapper.writeValueAsString(modelo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el modelo canónico", e);
        }
    }

    private String escribirPayload(Map<String, Object> payload) {
        try {
            return payload == null ? "{}" : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw MutacionRechazadaException.payloadInvalido(
                    "El payload no se puede serializar: " + e.getMessage());
        }
    }
}
