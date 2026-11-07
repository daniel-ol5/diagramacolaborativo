package com.modelcollab.assistant.settings;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CU21: Configuración del comportamiento de la IA por proyecto.
 *
 * <ul>
 *   <li>{@code GET /api/v1/projects/{projectId}/assistant-settings}: Cualquier miembro puede consultar
 *       los ajustes y recibe {@code canEdit} para saber si puede modificarlos.</li>
 *   <li>{@code PUT /api/v1/projects/{projectId}/assistant-settings}: Solo el {@code OWNER} puede guardar;
 *       los demás roles reciben 403 Forbidden.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assistant-settings")
public class AssistantSettingsController {

    private final AssistantSettingsService service;

    public AssistantSettingsController(AssistantSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public AssistantSettingsResponse getSettings(
            @PathVariable UUID projectId, Principal emisor) {
        return service.getSettings(projectId, userId(emisor));
    }

    @PutMapping
    public AssistantSettingsResponse updateSettings(
            @PathVariable UUID projectId,
            @RequestBody AssistantSettingsUpdateRequest request,
            Principal emisor) {
        return service.updateSettings(projectId, userId(emisor), request);
    }

    private static UUID userId(Principal emisor) {
        return UUID.fromString(emisor.getName());
    }
}

