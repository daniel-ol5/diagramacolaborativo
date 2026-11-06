package com.modelcollab.assistant.ai;

import java.util.Objects;

/** Ajustes de la IA del chat para un proyecto. Un campo nulo toma el valor por defecto. */
public record AssistantSettings(
        AssistantMode mode,
        AssistantLevel level,
        AssistantProvider provider,
        String localEndpoint,
        Integer maxTokens,
        Integer summaryFrequency) {

    public static final int DEFAULT_MAX_TOKENS = 400;
    public static final int DEFAULT_SUMMARY_FREQUENCY = 30;

    public static final AssistantSettings DEFAULTS =
            new AssistantSettings(
                    AssistantMode.ON_MENTION,
                    AssistantLevel.INTERMEDIATE,
                    AssistantProvider.CLOUD,
                    null,
                    DEFAULT_MAX_TOKENS,
                    DEFAULT_SUMMARY_FREQUENCY);

    public AssistantSettings(AssistantMode mode, AssistantLevel level, AssistantProvider provider) {
        this(mode, level, provider, null, DEFAULT_MAX_TOKENS, DEFAULT_SUMMARY_FREQUENCY);
    }

    public AssistantSettings {
        mode = Objects.requireNonNullElse(mode, AssistantMode.ON_MENTION);
        level = Objects.requireNonNullElse(level, AssistantLevel.INTERMEDIATE);
        provider = Objects.requireNonNullElse(provider, AssistantProvider.CLOUD);
        maxTokens = Objects.requireNonNullElse(maxTokens, DEFAULT_MAX_TOKENS);
        summaryFrequency = Objects.requireNonNullElse(summaryFrequency, DEFAULT_SUMMARY_FREQUENCY);
    }
}
