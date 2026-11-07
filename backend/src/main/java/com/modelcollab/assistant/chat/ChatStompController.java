package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * Entrada STOMP del chat del proyecto (CU19): SEND a {@code /app/project/{projectId}/chat}.
 *
 * <p>La membresía ya la exigió el {@code StompChannelInterceptor}, pero {@link ChatService} la
 * vuelve a mirar: es la regla del servicio, no de este canal. Aquí sólo se traduce:
 *
 * <ul>
 *   <li>El autor sale <b>siempre</b> del Principal y del Participante de la sesión. El cuerpo no
 *       tiene campos de autor y el ObjectMapper canónico rechaza los desconocidos.
 *   <li>La difusión a {@code /topic/projects/{id}/chat} la hace {@code StompChatPublisher} desde
 *       el servicio; aquí no se publica nada en el topic.
 *   <li>Un rechazo vuelve al emisor por {@code /user/queue/errors} con {@link StompErrorMessage}
 *       y el {@code clientMessageId} en {@code clientMutationId}; no sale ninguna excepción, así
 *       la sesión no se cierra.
 * </ul>
 */
@Controller
public class ChatStompController {

    private static final Logger log = LoggerFactory.getLogger(ChatStompController.class);

    static final String COLA_ERRORES = "/queue/errors";

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatStompController(ChatService chatService, SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/project/{projectId}/chat")
    public void enviar(
            @DestinationVariable String projectId,
            ChatPostRequest cuerpo,
            StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        String destino = destinoDe(projectId);
        String clientMessageId = cuerpo == null ? null : cuerpo.clientMessageId();
        if (principal == null) {
            // El interceptor no lo deja pasar; si llega, no hay a quién avisarle.
            log.warn("Mensaje de chat sin Principal en {}; se descarta", destino);
            return;
        }

        UUID idProyecto;
        try {
            idProyecto = UUID.fromString(projectId);
        } catch (IllegalArgumentException e) {
            responderError(principal, destino, clientMessageId,
                    ChatErrorCode.INVALID_PAYLOAD, "El identificador del proyecto no es válido");
            return;
        }

        Participante deLaSesion = StompChannelInterceptor.participanteDe(accessor);
        try {
            chatService.postFromUser(idProyecto, principal, cuerpo, deLaSesion);
        } catch (ChatRejectedException e) {
            responderError(principal, destino, clientMessageId, e.code(), e.getMessage());
        }
    }

    /**
     * Cuerpo ilegible o con campos desconocidos (por ejemplo un {@code authorId}): el converter
     * falla antes de llegar a {@link #enviar}. Sin esto el cliente no se enteraría de nada.
     * Sólo cubre los handlers de este controlador.
     */
    @MessageExceptionHandler(MessageConversionException.class)
    public void cuerpoInvalido(MessageConversionException e, StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            return;
        }
        log.debug("Cuerpo de chat inválido en {}: {}", accessor.getDestination(), e.getMessage());
        responderError(principal, accessor.getDestination(), null,
                ChatErrorCode.INVALID_PAYLOAD, "El mensaje no tiene un formato válido");
    }

    private void responderError(
            Principal principal, String destino, String clientMessageId,
            ChatErrorCode code, String motivo) {
        log.debug("{} en {}: {}", code, destino, motivo);
        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                COLA_ERRORES,
                new StompErrorMessage(code.status(), code.name(), destino, clientMessageId, motivo));
    }

    private static String destinoDe(String projectId) {
        return "/app/project/" + projectId + "/chat";
    }
}
