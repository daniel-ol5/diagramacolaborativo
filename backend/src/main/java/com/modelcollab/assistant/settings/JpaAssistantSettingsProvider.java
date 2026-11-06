package com.modelcollab.assistant.settings;

import com.modelcollab.assistant.ai.AssistantSettings;
import com.modelcollab.assistant.ai.AssistantSettingsProvider;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proveedor de ajustes reales almacenados en PostgreSQL.
 * Marcado con {@code @Primary} para que {@link com.modelcollab.assistant.ai.AssistantOrchestrator}
 * use esta implementación en lugar de {@link com.modelcollab.assistant.ai.DefaultAssistantSettingsProvider}.
 */
@Component
@Primary
public class JpaAssistantSettingsProvider implements AssistantSettingsProvider {

    private final AssistantSettingsRepository repository;

    public JpaAssistantSettingsProvider(AssistantSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantSettings forProject(UUID projectId) {
        if (projectId == null) {
            return AssistantSettings.DEFAULTS;
        }
        return repository.findById(projectId)
                .map(AssistantSettingsEntity::toDomain)
                .orElse(AssistantSettings.DEFAULTS);
    }
}

