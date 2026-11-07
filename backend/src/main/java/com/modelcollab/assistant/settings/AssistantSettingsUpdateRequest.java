package com.modelcollab.assistant.settings;

import com.modelcollab.assistant.ai.AssistantLevel;
import com.modelcollab.assistant.ai.AssistantMode;
import com.modelcollab.assistant.ai.AssistantProvider;
import com.modelcollab.assistant.ai.AssistantSettings;

public record AssistantSettingsUpdateRequest(
        AssistantMode mode,
        AssistantLevel level,
        AssistantProvider provider,
        String localEndpoint,
        Integer maxTokens,
        Integer summaryFrequency) {

    public AssistantSettings toDomain() {
        return new AssistantSettings(
                mode,
                level,
                provider,
                localEndpoint,
                maxTokens,
                summaryFrequency);
    }
}

