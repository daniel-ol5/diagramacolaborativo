package com.modelcollab.assistant.summary;

import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.project.model.ProjectRole;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints REST para la generación y consulta de resúmenes de conversación (CU24).
 *
 * <ul>
 *   <li>{@code POST /api/v1/projects/{projectId}/chat/summarize}: Disparo manual del resumen.
 *       Solo permitido para OWNER o EDITOR; VIEWER o no miembros reciben 403 Forbidden.</li>
 *   <li>{@code GET /api/v1/projects/{projectId}/chat/summaries/latest}: Consulta del último resumen.
 *       Permitido para cualquier miembro del proyecto (OWNER, EDITOR, VIEWER).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
public class ChatSummaryController {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryController.class);

    private final ChatSummaryService summaryService;
    private final ChatParticipants participants;

    public ChatSummaryController(ChatSummaryService summaryService, ChatParticipants participants) {
        this.summaryService = summaryService;
        this.participants = participants;
    }

    @PostMapping("/summarize")
    public ResponseEntity<ChatSummaryResponse> summarize(
            @PathVariable UUID projectId,
            Principal principal) {
        UUID userId = extractUserId(principal);
        Optional<ProjectRole> role = participants.roleIn(projectId, userId);

        if (role.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No eres miembro de este proyecto");
        }
        if (role.get() == ProjectRole.VIEWER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Solo OWNER o EDITOR pueden solicitar un resumen manual");
        }

        try {
            Optional<ChatSummaryResponse> response = summaryService.summarize(projectId, ChatSummaryTrigger.MANUAL);
            return response.map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (Exception e) {
            log.error("Error al generar resumen para el proyecto {}: {}", projectId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Error al generar resumen: " + e.getMessage(), e);
        }
    }

    @GetMapping("/summaries/latest")
    public ResponseEntity<ChatSummaryResponse> getLatest(
            @PathVariable UUID projectId,
            Principal principal) {
        UUID userId = extractUserId(principal);
        Optional<ProjectRole> role = participants.roleIn(projectId, userId);

        if (role.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No eres miembro de este proyecto");
        }

        return summaryService.getLatestSummary(projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static UUID extractUserId(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identificador de usuario inválido");
        }
    }
}

