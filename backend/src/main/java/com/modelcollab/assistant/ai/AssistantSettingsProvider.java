package com.modelcollab.assistant.ai;

import java.util.UUID;

/**
 * Los ajustes de la IA para un proyecto. Hoy la única implementación es {@link
 * DefaultAssistantSettingsProvider}; la de ajustes reales se registra como bean {@code @Primary}
 * y la reemplaza sin tocar a quien la usa.
 */
public interface AssistantSettingsProvider {

    /** Nunca nulo: si el proyecto no tiene ajustes, {@link AssistantSettings#DEFAULTS}. */
    AssistantSettings forProject(UUID projectId);
}
