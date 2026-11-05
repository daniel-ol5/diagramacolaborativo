package com.modelcollab.assistant.ai;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Siempre ON_MENTION, INTERMEDIATE, CLOUD, para todo proyecto. */
@Component
public class DefaultAssistantSettingsProvider implements AssistantSettingsProvider {

    @Override
    public AssistantSettings forProject(UUID projectId) {
        return AssistantSettings.DEFAULTS;
    }
}
