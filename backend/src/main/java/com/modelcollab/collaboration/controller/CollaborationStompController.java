package com.modelcollab.collaboration.controller;

import com.modelcollab.collaboration.dto.CursorBroadcastMessage;
import com.modelcollab.collaboration.dto.CursorMessage;
import com.modelcollab.collaboration.dto.LockMessage;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.collaboration.service.LockManagerService;
import com.modelcollab.collaboration.service.RoomManager;
import com.modelcollab.collaboration.service.MutacionRechazadaException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * Los dos destinos de aplicación de la colaboración: mutar el diagrama y pedir/soltar locks.
 *
 * <p>Es deliberadamente delgado. Cuando llega acá, el {@code StompChannelInterceptor} ya
 * garantizó que la sesión está autenticada y que el usuario es OWNER o EDITOR del proyecto; la
 * semántica de las operaciones vive en {@code DiagramMutationService} y la exclusión mutua en
 * {@code LockManagerService}. Lo único que hace este controlador es traducir entre STOMP y esos
 * servicios, y decidir <b>cuándo</b> se difunde.
 */
@Controller
public class CollaborationStompController {

    private static final Logger log = LoggerFactory.getLogger(CollaborationStompController.class);

    private final DiagramMutationService mutationService;
    private final LockManagerService lockManager;
    private final RoomManager roomManager;
    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationStompController(
            DiagramMutationService mutationService,
            LockManagerService lockManager,
            RoomManager roomManager,
            SimpMessagingTemplate messagingTemplate) {
        this.mutationService = mutationService;
        this.lockManager = lockManager;
        this.roomManager = roomManager;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Aplica una mutación y la difunde a la sala.
     *
     * <p>La difusión ocurre <b>después</b> de que {@code aplicar} retorna, o sea después del
     * commit: si el guardado falla, nadie llegó a ver el cambio. Al revés, los clientes se
     * quedarían mostrando algo que el servidor no tiene y sin forma de enterarse.
     */
    @MessageMapping("/diagram/{diagramId}/mutate")
    public void mutate(
            @DestinationVariable String diagramId,
            StompMutationMessage mensaje,
            StompHeaderAccessor accessor) {

        UUID idDiagrama = UUID.fromString(diagramId);
        UUID userId = UUID.fromString(accessor.getUser().getName());
        String destino = "/app/diagram/" + diagramId + "/mutate";

        try {
            var resultado = mutationService.aplicar(idDiagrama, userId, mensaje);

            if (resultado instanceof DiagramMutationService.Resultado.Aplicada aplicada) {
                messagingTemplate.convertAndSend(
                        "/topic/diagrams/" + diagramId, aplicada.difusion());

                // §8.3 paso 8: la operación que exigía lock lo suelta al confirmarse. Sin esto
                // el elemento queda bloqueado los 5 s del TTL después de terminar la edición, y
                // el que quiera seguir editando ve un candado sin motivo.
                if (aplicada.op().requiereLock()) {
                    lockManager.liberar(idDiagrama, aplicada.targetId(), userId);
                }
            } else {
                // Reintento del cliente con un clientMutationId ya aplicado. No se vuelve a
                // difundir: la sala ya lo recibió y repetirlo sería un evento fantasma.
                log.debug("Reintento duplicado ignorado en el diagrama {}", diagramId);
            }
        } catch (MutacionRechazadaException e) {
            responderError(userId, destino, mensaje.clientMutationId(), e);
        }
    }

    /**
     * Adquisición, renovación y liberación de soft-locks (§8.2).
     *
     * <p>El servicio ya se encarga de difundir la concesión a la sala y de mandarle la
     * denegación sólo al solicitante, así que acá no hay difusión: tener el protocolo de locks
     * repartido entre el servicio y el controlador sería la forma más fácil de que un camino
     * quedara sin avisar a nadie.
     */
    @MessageMapping("/diagram/{diagramId}/lock")
    public void lock(
            @DestinationVariable String diagramId,
            LockMessage mensaje,
            StompHeaderAccessor accessor) {

        UUID idDiagrama = UUID.fromString(diagramId);
        UUID userId = UUID.fromString(accessor.getUser().getName());
        String destino = "/app/diagram/" + diagramId + "/lock";

        if (mensaje.targetId() == null || mensaje.targetId().isBlank()) {
            responderError(userId, destino, null, MutacionRechazadaException.payloadInvalido(
                    "El mensaje de lock necesita un targetId"));
            return;
        }

        Participante quien = StompChannelInterceptor.participanteDe(accessor);
        if (quien == null) {
            // El CONNECT lo deja siempre puesto; si falta, algo se rompió en el camino y es
            // preferible no conceder un lock a nombre de un desconocido.
            log.warn("Mensaje de lock sin Participante en la sesión {}", accessor.getSessionId());
            return;
        }

        String accion = mensaje.action() == null ? "" : mensaje.action().toUpperCase();
        switch (accion) {
            case LockMessage.ACQUIRE, LockMessage.HEARTBEAT ->
                    lockManager.adquirir(
                            idDiagrama, mensaje.targetId(), quien, accessor.getSessionId());
            case LockMessage.RELEASE ->
                    lockManager.liberar(idDiagrama, mensaje.targetId(), userId);
            default -> responderError(userId, destino, null,
                    MutacionRechazadaException.payloadInvalido(
                            "Acción de lock desconocida: " + mensaje.action()
                                    + ". Esperaba ACQUIRE, HEARTBEAT o RELEASE"));
        }
    }

    /**
     * Cursor de un colaborador (§8.1, "canal volátil"). Se reemite tal cual a la sala.
     *
     * <p>Las tres cosas que <b>no</b> hace, y que son el punto de que exista este destino aparte
     * de {@code /mutate}:
     *
     * <ul>
     *   <li><b>No persiste nada.</b> Ni en {@code current_state} ni en la bitácora. Es presencia,
     *       no historia; guardar cursores llenaría {@code diagram_operations} de basura a veinte
     *       mensajes por segundo por usuario.</li>
     *   <li><b>No consume número de secuencia.</b> Si lo consumiera, un hueco en la numeración
     *       dejaría de significar "me perdí una mutación", que es exactamente para lo que el
     *       cliente lo usa al reconectar.</li>
     *   <li><b>No pide lock ni bloquea nada.</b> Un {@code VIEWER} puede emitirlo: el
     *       interceptor lo deja pasar a propósito.</li>
     * </ul>
     *
     * <p>Perder un cursor no tiene consecuencias — el siguiente lo reemplaza en milisegundos —,
     * y es justamente eso lo que permite mandarlos a la frecuencia del movimiento del mouse.
     */
    @MessageMapping("/diagram/{diagramId}/cursor")
    public void cursor(
            @DestinationVariable String diagramId,
            CursorMessage cursor,
            StompHeaderAccessor accessor) {

        Participante quien = StompChannelInterceptor.participanteDe(accessor);
        if (quien == null) {
            return; // sin CONNECT autenticado no hay a quién atribuirle el cursor
        }
        messagingTemplate.convertAndSend(
                "/topic/diagrams/" + diagramId, CursorBroadcastMessage.de(quien, cursor));
    }

    /**
     * "¿Quién está en la sala?". Responde el roster al solicitante por {@code /user/queue/presence}.
     *
     * <p>Hace falta un pedido explícito porque el recién llegado <b>no puede confiar</b> en la
     * difusión que lo incluye: el registro de su suscripción en el broker y el evento que
     * dispara la difusión van por caminos distintos. El cliente lo llama una vez, cuando ya
     * terminó de suscribirse a todo; de ahí en más las altas y bajas ajenas le llegan solas.
     */
    @MessageMapping("/diagram/{diagramId}/presence")
    public void presencia(
            @DestinationVariable String diagramId, StompHeaderAccessor accessor) {
        roomManager.responderRoster(
                UUID.fromString(diagramId), UUID.fromString(accessor.getUser().getName()));
    }

    /**
     * Los rechazos van al canal privado del emisor y nunca al topic: que a alguien le falte el
     * lock o mande un payload inválido no es información de la sala. Mismo canal y mismo formato
     * que el 403 por rol del Día 2, para que el cliente tenga un solo lugar donde escuchar
     * errores.
     */
    private void responderError(
            UUID userId, String destino, String clientMutationId, MutacionRechazadaException e) {
        log.debug("{} en {}: {}", e.getCode(), destino, e.getMessage());
        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/errors",
                new StompErrorMessage(
                        e.getStatus(), e.getCode(), destino, clientMutationId, e.getMessage()));
    }
}
