package com.modelcollab.assistant.settings;

import com.modelcollab.assistant.ai.AssistantMode;
import com.modelcollab.assistant.ai.AssistantProvider;
import com.modelcollab.assistant.ai.AssistantSettings;
import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.project.model.ProjectRole;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssistantSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AssistantSettingsService.class);

    private final AssistantSettingsRepository repository;
    private final ChatParticipants participants;
    private final ChatService chatService;

    public AssistantSettingsService(
            AssistantSettingsRepository repository,
            ChatParticipants participants,
            ChatService chatService) {
        this.repository = repository;
        this.participants = participants;
        this.chatService = chatService;
    }

    @Transactional(readOnly = true)
    public AssistantSettingsResponse getSettings(UUID projectId, UUID userId) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(userId, "userId");

        Optional<ProjectRole> role = participants.roleIn(projectId, userId);
        if (role.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No eres miembro de este proyecto");
        }

        boolean canEdit = role.get() == ProjectRole.OWNER;
        AssistantSettings settings = repository.findById(projectId)
                .map(AssistantSettingsEntity::toDomain)
                .orElse(AssistantSettings.DEFAULTS);

        return AssistantSettingsResponse.fromDomain(settings, canEdit);
    }

    @Transactional
    public AssistantSettingsResponse updateSettings(
            UUID projectId, UUID userId, AssistantSettingsUpdateRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(request, "request");

        Optional<ProjectRole> role = participants.roleIn(projectId, userId);
        if (role.isEmpty() || role.get() != ProjectRole.OWNER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Solo el propietario puede modificar la configuración de la IA");
        }

        AssistantSettings nuevo = request.toDomain();
        validarSettings(nuevo);

        AssistantSettingsEntity entity = repository.findById(projectId)
                .orElseGet(() -> new AssistantSettingsEntity(projectId));

        AssistantSettings anterior = entity.toDomain();
        entity.updateFrom(nuevo, userId);
        repository.save(entity);

        // Notificar al chat con un mensaje de sistema
        notificarCambioEnChat(projectId, userId, anterior, nuevo);

        return AssistantSettingsResponse.fromDomain(entity.toDomain(), true);
    }

    private void validarSettings(AssistantSettings settings) {
        if (settings.provider() == AssistantProvider.LOCAL) {
            String endpoint = settings.localEndpoint();
            if (endpoint == null || endpoint.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Debe especificar la URL del servidor local");
            }
            try {
                URI uri = URI.create(endpoint.trim());
                String scheme = uri.getScheme();
                if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "La URL del servidor local debe comenzar con http:// o https://");
                }
            } catch (Exception e) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "La URL del servidor local no es válida: " + e.getMessage());
            }
        }
    }

    private void notificarCambioEnChat(
            UUID projectId, UUID userId, AssistantSettings anterior, AssistantSettings nuevo) {
        try {
            String autor = participants.fullName(userId).orElse("El propietario");
            String descripcionModo = descripcionModo(nuevo.mode());
            String mensaje = "%s actualizó la configuración de la IA (Modo: %s, Nivel: %s, Proveedor: %s)"
                    .formatted(autor, descripcionModo, nuevo.level().name(), nuevo.provider().name());

            chatService.postFromAi(projectId, ChatAuthorType.SYSTEM, mensaje, null);
        } catch (Exception e) {
            // El aviso en chat es informativo: no debe abortar la transacción de guardado
            log.warn("No se pudo publicar el aviso de cambio de configuración en el chat: {}", e.getMessage());
        }
    }

    private static String descripcionModo(AssistantMode mode) {
        return switch (mode) {
            case OFF -> "Desactivada";
            case ON_MENTION -> "Solo mención (@IA)";
            case ALWAYS -> "Siempre activa";
            case MENTOR_PROACTIVE -> "Mentor proactivo";
        };
    }
}

