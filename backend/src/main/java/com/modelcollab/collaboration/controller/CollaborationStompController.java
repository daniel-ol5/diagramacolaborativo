package com.modelcollab.collaboration.controller;

import com.modelcollab.collaboration.dto.LockMessage;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.collaboration.service.LockManagerService;
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
    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationStompController(
            DiagramMutationService mutationService,
            LockManagerService lockManager,
            SimpMessagingTemplate messagingTemplate) {
        this.mutationService = mutationService;
        this.lockManager = lockManager;
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
