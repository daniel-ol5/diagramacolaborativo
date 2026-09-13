package com.modelcollab.ai.service;

import com.modelcollab.ai.dto.AiCommandRequest;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.dto.AiPlan;
import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.collaboration.service.LockManagerService;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.service.PreGenerationValidatorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.service.MetamodelValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU16: convierte un comando de voz o de texto en operaciones sobre el diagrama.
 *
 * <h2>El orden de las comprobaciones es el diseño</h2>
 *
 * <ol>
 *   <li><b>Idempotencia</b>, antes que nada: un reintento no vuelve a gastar una llamada al
 *       servicio ni crea dos veces la misma clase.</li>
 *   <li><b>Guardarraíl local</b>, antes de la red: "creame un sistema para un banco" se rechaza
 *       sin llamar a nadie, así que la respuesta a la pregunta de §19 no depende de que haya
 *       internet el día de la defensa.</li>
 *   <li><b>Contrato de salida</b>: lo que devuelve el modelo se valida con la misma severidad
 *       que lo que manda un navegador.</li>
 *   <li><b>Tope duro de 5 operaciones.</b></li>
 *   <li><b>Ensayo en seco</b> y comprobación de que el diagrama no empeora.</li>
 *   <li><b>Locks</b>: la IA no puentea la exclusión mutua.</li>
 *   <li><b>Aplicar</b> por el mismo camino que una mutación del lienzo.</li>
 * </ol>
 *
 * <p>Los pasos caros van después de los baratos, pero eso es un efecto secundario: el orden lo
 * decide qué comprobación protege a cuál. No tendría sentido validar el contrato de una respuesta
 * que no había que pedir.
 *
 * <h2>Todo o nada</h2>
 *
 * <p>El método es transaccional y las difusiones salen recién cuando el llamador las manda, con
 * el commit hecho. Si la cuarta de cinco operaciones falla, no queda ninguna aplicada. Medio
 * comando es un diagrama que el usuario no pidió, no sabe cómo quedó y no sabe cómo deshacer —y
 * en una sala colaborativa, además, los demás ya lo estarían viendo.
 */
@Service
public class AiCommandService {

    private static final Logger log = LoggerFactory.getLogger(AiCommandService.class);

    /**
     * Tope duro de RF-02.6. <b>Constante y no configuración</b>, a propósito: un tope que se
     * puede subir con una variable de entorno no es un tope, es un valor por defecto, y el día
     * que alguien lo suba para "probar algo" el guardarraíl deja de existir sin que nadie toque
     * una línea de código.
     *
     * <p>Cinco alcanza para el comando más largo que tiene sentido dictar —crear una clase con
     * cuatro atributos— y no alcanza para generar un dominio, que es exactamente la línea que
     * hay que trazar.
     */
    public static final int MAXIMO_DE_OPERACIONES = 5;

    /** Tope de lo que se muestra del texto libre del modelo. */
    private static final int MAXIMO_DEL_MENSAJE = 300;

    /** Lo que hay que difundir a la sala una vez commiteado. */
    public record Resultado(AiCommandResponse respuesta, List<StompBroadcastMessage> difusiones) {}

    private final AiModelClient cliente;
    private final CommandGuardrail guardarrail;
    private final AiPlanParser parser;
    private final OperationTranslator traductor;
    private final MetamodelValidator validador;
    private final PreGenerationValidatorService estados;
    private final DiagramMutationService mutationService;
    private final DiagramOperationRepository operationRepository;
    private final LockManagerService lockManager;
    private final UserRepository userRepository;

    public AiCommandService(
            AiModelClient cliente,
            CommandGuardrail guardarrail,
            AiPlanParser parser,
            OperationTranslator traductor,
            MetamodelValidator validador,
            PreGenerationValidatorService estados,
            DiagramMutationService mutationService,
            DiagramOperationRepository operationRepository,
            LockManagerService lockManager,
            UserRepository userRepository) {
        this.cliente = cliente;
        this.guardarrail = guardarrail;
        this.parser = parser;
        this.traductor = traductor;
        this.validador = validador;
        this.estados = estados;
        this.mutationService = mutationService;
        this.operationRepository = operationRepository;
        this.lockManager = lockManager;
        this.userRepository = userRepository;
    }

    @Transactional
    public Resultado procesar(UUID diagramId, UUID userId, AiCommandRequest peticion) {

        String comandoId = peticion.clientCommandId() == null
                || peticion.clientCommandId().isBlank()
                ? UUID.randomUUID().toString()
                : peticion.clientCommandId().strip();

        if (yaSeHabiaAplicado(diagramId, peticion, comandoId)) {
            return soloRespuesta(AiCommandResponse.rechazada(
                    AiCommandResponse.YA_APLICADO,
                    "Ese comando ya se había aplicado antes; no se repitió."));
        }

        // Antes de la red. Es lo que hace que el rechazo funcione con el servicio caído.
        if (guardarrail.pideUnDominioEntero(peticion.command())) {
            log.debug("Guardarraíl local: comando rechazado sin llamar al modelo");
            return soloRespuesta(AiCommandResponse.rechazada(
                    AiCommandResponse.GENERAR_DOMINIO, CommandGuardrail.RECHAZO));
        }

        CanonicalModel estado = estados.estadoDe(diagramId);
        AiPlan plan = parser.parsear(consultar(peticion.command(), estado));

        if (AiPlan.GENERAR_DOMINIO.equals(plan.intent())) {
            // El modelo se autodenunció. Se le cree para rechazar, nunca para aceptar.
            return soloRespuesta(AiCommandResponse.rechazada(
                    AiCommandResponse.GENERAR_DOMINIO, CommandGuardrail.RECHAZO));
        }
        if (plan.operations().size() > MAXIMO_DE_OPERACIONES) {
            log.debug("Tope duro: el comando producía {} operaciones", plan.operations().size());
            return soloRespuesta(AiCommandResponse.rechazada(
                    AiCommandResponse.DEMASIADAS_OPERACIONES,
                    "Ese comando produce " + plan.operations().size() + " operaciones y el "
                            + "máximo es " + MAXIMO_DE_OPERACIONES + ". Pedímelo en partes: "
                            + "una clase o un par de atributos por vez."));
        }
        if (plan.operations().isEmpty()) {
            return soloRespuesta(AiCommandResponse.rechazada(
                    AiCommandResponse.NO_ENTENDIDO,
                    recortar(plan.reply(), "No entendí qué querés cambiar. Probá con algo como "
                            + "\"crea la clase Factura con atributo total decimal\".")));
        }

        OperationTranslator.Resultado traduccion =
                traductor.traducir(estado, plan.operations());

        List<String> empeoraria = erroresNuevos(estado, traduccion.modeloResultante());
        if (!empeoraria.isEmpty()) {
            // La IA no puede dejar el diagrama peor de lo que estaba. Si ya estaba roto, no es
            // culpa de este comando y no se le reprocha: sólo cuentan los errores NUEVOS.
            return soloRespuesta(AiCommandResponse.rechazada(
                    AiCommandResponse.ROMPERIA_EL_DIAGRAMA,
                    "No lo apliqué porque dejaría el diagrama sin poder generarse: "
                            + String.join(" ", empeoraria)));
        }

        return aplicar(diagramId, userId, comandoId, plan, traduccion);
    }

    // ------------------------------------------------------------- aplicar

    private Resultado aplicar(
            UUID diagramId,
            UUID userId,
            String comandoId,
            AiPlan plan,
            OperationTranslator.Resultado traduccion) {

        Set<String> lockesTomados = new HashSet<>();
        try {
            Participante quien = participante(userId);
            // Sesión sintética: este "colaborador" no tiene conexión WebSocket, así que nunca
            // va a llegar un DISCONNECT que libere sus locks. Por eso se liberan a mano en el
            // finally, y el TTL de 5 segundos queda de red de seguridad si algo se cae antes.
            String sesion = "ai-" + comandoId;

            for (OperationTranslator.Traduccion t : traduccion.operaciones()) {
                OperationType op = OperationType.valueOf(t.mensaje().op());
                if (!op.requiereLock()) {
                    continue;
                }
                LockManagerService.Resultado resultado =
                        lockManager.adquirir(diagramId, t.mensaje().targetId(), quien, sesion);
                if (resultado instanceof LockManagerService.Resultado.Denegado denegado) {
                    // La IA pasa por la misma exclusión mutua que cualquiera. Si no, sería la
                    // puerta de atrás: lo que el lienzo impide se conseguiría dictándolo.
                    return soloRespuesta(AiCommandResponse.rechazada(
                            AiCommandResponse.ELEMENTO_BLOQUEADO,
                            "No lo apliqué porque " + denegado.heldBy()
                                    + " está editando eso en este momento. Probá en unos "
                                    + "segundos."));
                }
                if (resultado instanceof LockManagerService.Resultado.Concedido concedido
                        && !concedido.renovacion()) {
                    // Sólo se devuelve lo que se tomó: si el usuario ya tenía el lock porque
                    // está editando esa clase en el lienzo, soltárselo le sacaría el candado
                    // de las manos en medio de su propia edición.
                    lockesTomados.add(t.mensaje().targetId());
                }
            }

            List<String> descripciones = new ArrayList<>();
            List<StompBroadcastMessage> difusiones = new ArrayList<>();
            long version = 0;

            int numero = 1;
            for (OperationTranslator.Traduccion t : traduccion.operaciones()) {
                StompMutationMessage conIdentidad = new StompMutationMessage(
                        t.mensaje().op(),
                        t.mensaje().targetId(),
                        identificadorDeMutacion(comandoId, numero++),
                        t.mensaje().payload());

                DiagramMutationService.Resultado resultado =
                        mutationService.aplicar(diagramId, userId, conIdentidad);
                if (resultado instanceof DiagramMutationService.Resultado.Aplicada aplicada) {
                    difusiones.add(aplicada.difusion());
                    version = aplicada.difusion().sequenceNum();
                }
                descripciones.add(t.descripcion());
            }

            log.debug("Comando de IA aplicado a {}: {} operación(es)", diagramId,
                    descripciones.size());
            return new Resultado(
                    AiCommandResponse.aplicada(
                            descripciones,
                            recortar(plan.reply(), String.join(" ", descripciones)),
                            version),
                    difusiones);
        } finally {
            lockesTomados.forEach(targetId -> lockManager.liberar(diagramId, targetId, userId));
        }
    }

    // ------------------------------------------------------------ auxiliares

    /**
     * Consulta al modelo y traduce cualquier fallo de la red a un error identificable.
     *
     * <p>Se distingue "el servicio no contestó" de "el servicio contestó cualquier cosa": son
     * dos problemas distintos, con dos culpables distintos y dos arreglos distintos, y meterlos
     * en el mismo mensaje haría que nadie supiera cuál de los dos está pasando.
     */
    private String consultar(String comando, CanonicalModel estado) {
        List<String> nombres = estado.classes().stream()
                .map(ClassEntity::name)
                .filter(n -> n != null && !n.isBlank())
                .toList();
        try {
            return cliente.responder(comando, nombres);
        } catch (RespuestaDeIaInvalidaException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("El servicio de IA ({}) no respondió: {}",
                    cliente.descripcion(), e.toString());
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.IA_CAIDA,
                    "El asistente no está disponible en este momento. El diagrama no se tocó.",
                    e);
        }
    }

    /**
     * Los errores bloqueantes que tendría el diagrama después y no tenía antes.
     *
     * <p>La comparación es por código y elemento, no por cantidad. Un diagrama que ya venía con
     * una relación huérfana la sigue teniendo después, y culpar de eso al comando sería
     * bloquear para siempre todo trabajo sobre un diagrama que alguien dejó roto.
     */
    private List<String> erroresNuevos(CanonicalModel antes, CanonicalModel despues) {
        Set<String> yaEstaban = new HashSet<>();
        validador.validar(antes).errors().forEach(e -> yaEstaban.add(firma(e)));

        return validador.validar(despues).errors().stream()
                .filter(e -> !yaEstaban.contains(firma(e)))
                .map(ValidationReportDTO.ValidationIssue::message)
                .toList();
    }

    private static String firma(ValidationReportDTO.ValidationIssue issue) {
        return issue.code() + "|" + issue.elementId();
    }

    private boolean yaSeHabiaAplicado(
            UUID diagramId, AiCommandRequest peticion, String comandoId) {
        if (peticion.clientCommandId() == null || peticion.clientCommandId().isBlank()) {
            return false;
        }
        // Alcanza con mirar la primera: un comando aplicado siempre aplicó al menos una
        // operación, y uno rechazado no aplicó ninguna.
        return operationRepository
                .findByDiagramIdAndClientMutationId(
                        diagramId, identificadorDeMutacion(comandoId, 1))
                .isPresent();
    }

    /**
     * El {@code clientMutationId} de cada operación del comando.
     *
     * <p>Lleva el id del comando adentro para que el reintento del mismo comando choque contra el
     * índice único de §13 operación por operación, igual que un reintento del lienzo.
     */
    private static String identificadorDeMutacion(String comandoId, int numero) {
        return "ai-" + comandoId + "-" + numero;
    }

    private Participante participante(UUID userId) {
        String nombre = userRepository.findById(userId)
                .map(User::getFullName)
                .orElse("Asistente");
        return Participante.de(userId, nombre);
    }

    /**
     * El texto libre del modelo, acotado.
     *
     * <p>Se muestra en pantalla y viene de afuera, así que no puede ser tan largo como al modelo
     * se le ocurra: un "reply" de cincuenta mil caracteres no es un ataque, pero rompe la
     * interfaz igual.
     */
    private static String recortar(String delModelo, String alternativa) {
        if (delModelo == null || delModelo.isBlank()) {
            return alternativa;
        }
        String limpio = delModelo.strip();
        return limpio.length() <= MAXIMO_DEL_MENSAJE
                ? limpio
                : limpio.substring(0, MAXIMO_DEL_MENSAJE) + "…";
    }

    private static Resultado soloRespuesta(AiCommandResponse respuesta) {
        return new Resultado(respuesta, List.of());
    }
}
