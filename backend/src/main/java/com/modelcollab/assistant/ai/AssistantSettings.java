package com.modelcollab.assistant.ai;

import java.util.Objects;

/** Ajustes de la IA del chat para un proyecto. Un campo nulo toma el valor por defecto. */
public record AssistantSettings(AssistantMode mode, AssistantLevel level, AssistantProvider provider) {

    public static final AssistantSettings DEFAULTS =
            new AssistantSettings(AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.CLOUD);

    public AssistantSettings {
        mode = Objects.requireNonNullElse(mode, AssistantMode.ON_MENTION);
        level = Objects.requireNonNullElse(level, AssistantLevel.INTERMEDIATE);
        provider = Objects.requireNonNullElse(provider, AssistantProvider.CLOUD);
    }
}
