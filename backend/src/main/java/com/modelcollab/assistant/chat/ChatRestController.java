package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.project.service.ProjectService;
import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Chat grupal del proyecto por REST (CU19), pensado para el móvil. Mismas reglas que STOMP:
 * todo pasa por {@link ChatService}.
 *
 * <h2>Contrato para los clientes (web y móvil)</h2>
 *
 * <pre>
 * POST /api/v1/projects/{projectId}/chat
 *   cuerpo: {clientMessageId, text, diagramId?, replyToId?}   (sin campos de autor)
 *   201 -> el mensaje guardado (nuevo)
 *   200 -> el mensaje ORIGINAL: era un reintento del mismo usuario con el mismo clientMessageId
 *
 * GET /api/v1/projects/{projectId}/chat?beforeSeq=&amp;afterSeq=&amp;limit=
 *   200 -> {messages: [...], hasMore: boolean}, messages SIEMPRE ascendente por seq
 *   sin cursores: los últimos `limit`;           hasMore = hay mensajes MÁS VIEJOS
 *   beforeSeq=N:  los inmediatamente anteriores; hasMore = hay mensajes MÁS VIEJOS
 *   afterSeq=N:   los inmediatamente posteriores (los más cercanos a N, no los últimos);
 *                 hasMore = hay mensajes MÁS NUEVOS: seguir pidiendo con el último seq recibido
 *   limit: 50 por defecto, acotado a 1..100. beforeSeq y afterSeq juntos: 400 INVALID_PAYLOAD.
 *
 * Mensaje: id, projectId, diagramId, seq, authorType, authorId, authorName, authorColor,
 *   addressedToId, addressedToName, replyToId, clientMessageId, kind, text, createdAt, meta
 *   - createdAt: texto ISO-8601 en UTC, p. ej. "2026-11-05T14:03:22.123Z" (nunca un número).
 *   - El mapper canónico omite los nulos (NON_NULL): diagramId, replyToId, addressedToId,
 *     addressedToName, meta y authorColor son OPCIONALES y pueden no venir en el JSON.
 *
 * Errores:
 *   403 (cuerpo estándar de la API, sin `code`) -> no sos miembro del proyecto. Cualquier 403
 *       significa eso: un proyecto inexistente responde igual, para no revelar qué ids existen.
 *   400/403/429 {code, message} -> rechazo del chat: INVALID_PAYLOAD (projectId o parámetro no
 *       válido, cuerpo ilegible o con campos desconocidos como authorId, texto inválido...),
 *       NOT_PROJECT_MEMBER, RATE_LIMITED. `message` es para mostrarle al usuario.
 * </pre>
 *
 * <p>Los {@code @ExceptionHandler} son locales a este controlador a propósito: no cambian cómo
 * responde ningún otro endpoint de la API.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
public class ChatRestController {

    private static final Logger log = LoggerFactory.getLogger(ChatRestController.class);

    /** Cuerpo de error del chat. */
    public record ChatErrorResponse(String code, String message) {}

    private final ChatService chatService;
    private final ProjectService projectService;

    public ChatRestController(ChatService chatService, ProjectService projectService) {
        this.chatService = chatService;
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ChatMessage> enviar(
            @PathVariable UUID projectId,
            @RequestBody ChatPostRequest peticion,
            Principal emisor) {
        projectService.exigirMembresia(projectId, userId(emisor));
        ChatPostResult resultado = chatService.postFromUser(projectId, emisor, peticion);
        return ResponseEntity.status(resultado.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(resultado.message());
    }

    @GetMapping
    public ChatHistoryPage historial(
            @PathVariable UUID projectId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Integer limit,
            Principal emisor) {
        projectService.exigirMembresia(projectId, userId(emisor));
        return chatService.history(projectId, emisor, beforeSeq, afterSeq, limit);
    }

    // ------------------------------------------------------------- errores

    @ExceptionHandler(ChatRejectedException.class)
    public ResponseEntity<ChatErrorResponse> rechazo(ChatRejectedException e) {
        return error(e.code(), e.getMessage());
    }

    /** projectId que no es UUID, o beforeSeq/afterSeq/limit no numéricos. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ChatErrorResponse> parametroInvalido(MethodArgumentTypeMismatchException e) {
        return error(ChatErrorCode.INVALID_PAYLOAD, "Parámetro inválido: " + e.getName());
    }

    /** Cuerpo ilegible o con campos desconocidos (el mapper canónico rechaza un authorId). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ChatErrorResponse> cuerpoInvalido(HttpMessageNotReadableException e) {
        log.debug("Cuerpo de chat inválido: {}", e.getMessage());
        return error(ChatErrorCode.INVALID_PAYLOAD, "El mensaje no tiene un formato válido");
    }

    private static ResponseEntity<ChatErrorResponse> error(ChatErrorCode code, String mensaje) {
        return ResponseEntity.status(code.status()).body(new ChatErrorResponse(code.name(), mensaje));
    }

    private static UUID userId(Principal emisor) {
        return UUID.fromString(emisor.getName());
    }
}
