package com.modelcollab.collaboration.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.model.DiagramOperation;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica una mutación al diagrama y la deja persistida. Es la pieza que convierte el andamio del
 * Día 2 en colaboración de verdad: hasta ayer el servidor reemitía mensajes, ahora es el dueño
 * del estado.
 *
 * <h2>Primero se persiste, después se difunde</h2>
 *
 * <p>Todo el trabajo ocurre dentro de una transacción y la difusión la hace el llamador
 * <b>cuando esta transacción ya commiteó</b>. El orden inverso —difundir y después guardar— deja
 * a los clientes pintando un estado que el servidor no tiene si el guardado falla, y sin ninguna
 * forma de enterarse: nadie les va a mandar un mensaje diciendo "aquello que te mostré no pasó".
 * Con este orden, un fallo se traduce en que la mutación simplemente no ocurrió para nadie, que
 * es un estado consistente.
 *
 * <h2>El lock se verifica acá y no en el interceptor</h2>
 *
 * <p>El interceptor responde "¿podés mutar este diagrama?" y está en la ruta caliente, donde no
 * debe deserializar payloads. La pregunta "¿podés mutar <b>este elemento</b> ahora mismo?"
 * necesita el mensaje ya tipado, y este es el sitio donde lo está.
 */
@Service
public class DiagramMutationService {

    private static final Logger log = LoggerFactory.getLogger(DiagramMutationService.class);

    /** Lo que hay que hacer después de commitear. */
    public sealed interface Resultado {
        /** Se aplicó: hay que difundirla a la sala. */
        record Aplicada(StompBroadcastMessage difusion, OperationType op, String targetId)
                implements Resultado {}

        /** Ya se había aplicado antes con el mismo clientMutationId: no se repite. */
        record Duplicada(long sequenceNumOriginal) implements Resultado {}
    }

    private final DiagramRepository diagramRepository;
    private final DiagramOperationRepository operationRepository;
    private final UserRepository userRepository;
    private final CanonicalMutationApplier applier;
    private final SequenceService sequenceService;
    private final LockManagerService lockManager;
    private final ObjectMapper objectMapper;

    public DiagramMutationService(
            DiagramRepository diagramRepository,
            DiagramOperationRepository operationRepository,
            UserRepository userRepository,
            CanonicalMutationApplier applier,
            SequenceService sequenceService,
            LockManagerService lockManager,
            ObjectMapper objectMapper) {
        this.diagramRepository = diagramRepository;
        this.operationRepository = operationRepository;
        this.userRepository = userRepository;
        this.applier = applier;
        this.sequenceService = sequenceService;
        this.lockManager = lockManager;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Resultado aplicar(UUID diagramId, UUID userId, StompMutationMessage mensaje) {

        OperationType op = OperationType.desde(mensaje.op())
                .orElseThrow(() -> new MutacionRechazadaException(
                        MutacionRechazadaException.OP_DESCONOCIDA, 400,
                        "Operación desconocida: " + mensaje.op()));

        // Idempotencia (§13, índice idx_diagram_ops_client_mut): un cliente que reintenta tras
        // una reconexión manda el mismo clientMutationId, y aplicar dos veces un ADD_ATTRIBUTE
        // duplicaría el atributo.
        //
        // Va ANTES de la comprobación de lock, y el orden importa: un reintento llega con la
        // sesión nueva y por lo tanto sin el lock, que murió con la sesión anterior. Con el
        // orden inverso, el cliente que reintenta una operación YA APLICADA recibiría un
        // LOCK_REQUIRED y creería que su cambio se perdió, cuando en realidad está guardado.
        // Un duplicado no es un intento de mutar: es la repetición de algo que ya se autorizó.
        //
        // Además se comprueba antes de consumir número de secuencia, para no dejar huecos en el
        // historial que lee el rebase offline del Día 10.
        Optional<DiagramOperation> yaAplicada = mensaje.clientMutationId() == null
                ? Optional.empty()
                : operationRepository.findByDiagramIdAndClientMutationId(
                        diagramId, mensaje.clientMutationId());
        if (yaAplicada.isPresent()) {
            log.debug("Mutación {} ya aplicada en la secuencia {}; se ignora",
                    mensaje.clientMutationId(), yaAplicada.get().getSequenceNum());
            return new Resultado.Duplicada(yaAplicada.get().getSequenceNum());
        }

        if (op.requiereLock()
                && !lockManager.poseeLock(diagramId, mensaje.targetId(), userId)) {
            // §8.1 marca estas operaciones como "Requiere Lock". Sin esta comprobación el lock
            // sería una convención que respetan los clientes bien portados, no una garantía.
            throw new MutacionRechazadaException(
                    MutacionRechazadaException.LOCK_REQUERIDO, 409,
                    "La operación " + op + " exige poseer el lock de " + mensaje.targetId());
        }

        // Incremento atómico en el motor. Además de dar el número, toma el lock de la fila del
        // diagrama hasta el commit, con lo que dos mutaciones simultáneas sobre el mismo
        // diagrama se aplican una después de la otra y nunca sobre la misma copia leída.
        long secuencia = sequenceService.siguiente(diagramId);

        Diagram diagrama = diagramRepository.findById(diagramId)
                .orElseThrow(() -> MutacionRechazadaException.objetivoInexistente(
                        "El diagrama", diagramId.toString()));

        CanonicalModel estado = leer(diagrama.getCurrentState());
        CanonicalModel nuevo = applier.apply(estado, op, mensaje.targetId(), mensaje.payload());

        CanonicalModel versionado = new CanonicalModel(
                nuevo.schemaVersion(), secuencia,
                nuevo.packages(), nuevo.classes(), nuevo.relationships());

        diagrama.setCurrentState(escribir(versionado));
        diagrama.setMutationVersion(secuencia);

        operationRepository.save(new DiagramOperation(
                diagrama,
                userRepository.getReferenceById(userId),
                op.name(),
                mensaje.targetId(),
                escribirPayload(mensaje, estado),
                mensaje.clientMutationId(),
                secuencia));

        log.debug("{} aplicada al diagrama {} en la secuencia {}", op, diagramId, secuencia);

        return new Resultado.Aplicada(
                StompBroadcastMessage.de(mensaje, userId.toString(), Instant.now(), secuencia),
                op,
                mensaje.targetId());
    }

    private CanonicalModel leer(String json) {
        try {
            return objectMapper.readValue(json, CanonicalModel.class);
        } catch (JsonProcessingException e) {
            // Si el JSONB guardado no deserializa, el diagrama está corrupto y aplicar encima
            // lo empeoraría. Se corta acá.
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

    /**
     * La columna {@code payload} es NOT NULL; una operación sin payload guarda un objeto vacío.
     *
     * <p>A los borrados se les agrega el nombre de lo que se borró, tomado del estado
     * <b>anterior</b> a aplicar la operación. Es el único momento en que ese dato existe: después
     * el elemento ya no está en ningún lado. Sin esto, el historial de un {@code DELETE_CLASS} es
     * un UUID sin significado, y el reporte de reconciliación de §9.2 no puede decir "la clase
     * 'Cliente' fue eliminada" porque no sabe cómo se llamaba.
     */
    private String escribirPayload(StompMutationMessage mensaje, CanonicalModel estadoPrevio) {
        Map<String, Object> payload = mensaje.payload() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(mensaje.payload());

        if (mensaje.op() != null && mensaje.op().startsWith("DELETE_")
                && !payload.containsKey("name")) {
            CanonicalMutationApplier.nombreDe(estadoPrevio, mensaje.targetId())
                    .ifPresent(nombre -> payload.put("name", nombre));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw MutacionRechazadaException.payloadInvalido(
                    "El payload no se puede serializar: " + e.getMessage());
        }
    }
}
