package com.modelcollab.assistant.summary;

import java.util.Optional;
import java.util.UUID;

/**
 * Puerto que provee el último resumen de conversación para que el orquestador
 * de IA (CU19) lo incorpore en el contexto sin saturar la ventana de tokens.
 */
public interface ChatSummaryProvider {

    /** Devuelve el último resumen estructurado del proyecto, si existe. */
    Optional<ChatSummaryData> latestSummary(UUID projectId);

    /** Devuelve el texto formateado del último resumen para incluir en el system prompt. */
    Optional<String> latestSummaryText(UUID projectId);
}

