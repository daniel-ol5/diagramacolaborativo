package com.modelcollab.assistant.settings;

import com.modelcollab.assistant.ai.AssistantLevel;
import com.modelcollab.assistant.ai.AssistantMode;
import com.modelcollab.assistant.ai.AssistantProvider;
import com.modelcollab.assistant.ai.AssistantSettings;

public record AssistantSettingsResponse(
        AssistantMode mode,
        AssistantLevel level,
        AssistantProvider provider,
        String localEndpoint,
        Integer maxTokens,
        Integer summaryFrequency,
        boolean canEdit) {

    public static AssistantSettingsResponse fromDomain(AssistantSettings settings, boolean canEdit) {
        return new AssistantSettingsResponse(
                settings.mode(),
                settings.level(),
                settings.provider(),
                settings.localEndpoint(),
                settings.maxTokens(),
                settings.summaryFrequency(),
                canEdit);
    }
}

