package com.modelcollab.collaboration.controller;

import com.modelcollab.collaboration.dto.StompBroadcastMessage;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import java.security.Principal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * ANDAMIO DEL DÍA 2 — reemplazar el Día 3.
 *
 * <p>Hoy sólo reemite lo que recibe, agregándole el {@code userId} de la sesión y el sello de
 * tiempo del servidor. Existe para poder verificar de punta a punta que el
 * {@code StompChannelInterceptor} deja pasar a OWNER y EDITOR y descarta a VIEWER: sin un
 * destino que responda, el criterio de aceptación del día no se puede observar desde un cliente.
 *
 * <p><b>Lo que le falta y llega el Día 3:</b> {@code SequenceService} (número de secuencia
 * monotónico por diagrama), persistencia en {@code diagram_operations} y actualización de
 * {@code diagrams.current_state}, {@code LockManagerService} (verificar que el emisor posee el
 * lock en las operaciones que lo requieren, §8.1), {@code LwwConflictResolver} y
 * {@code RoomManager}. Hasta entonces <b>ninguna mutación se persiste</b>: esto es un eco
 * autorizado, no colaboración.
 */
@Controller
public class CollaborationStompController {

    private static final Logger log = LoggerFactory.getLogger(CollaborationStompController.class);

    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationStompController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Al llegar acá el interceptor ya garantizó dos cosas: la sesión está autenticada y el
     * usuario es OWNER o EDITOR del proyecto. Por eso el {@code Principal} se puede usar sin
     * comprobarlo.
     */
    @MessageMapping("/diagram/{diagramId}/mutate")
    public void mutate(
            @DestinationVariable String diagramId,
            StompMutationMessage mensaje,
            Principal emisor) {

        log.debug("{} de {} en el diagrama {}", mensaje.op(), emisor.getName(), diagramId);

        messagingTemplate.convertAndSend(
                "/topic/diagrams/" + diagramId,
                StompBroadcastMessage.de(mensaje, emisor.getName(), Instant.now()));
    }
}
