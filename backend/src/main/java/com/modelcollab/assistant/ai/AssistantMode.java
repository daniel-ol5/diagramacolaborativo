package com.modelcollab.assistant.ai;

/** Cuándo responde la IA en el chat de un proyecto. */
public enum AssistantMode {
    /** Nunca. */
    OFF,
    /** Si la mencionan con @IA o le responden a un mensaje suyo. Por defecto. */
    ON_MENTION,
    /** A todo mensaje de una persona. */
    ALWAYS,
    /** Reservado para los ajustes reales (intervenciones por iniciativa propia). */
    MENTOR_PROACTIVE
}
