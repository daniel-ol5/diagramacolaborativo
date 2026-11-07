package com.modelcollab.assistant.summary;

import java.time.Instant;
import java.util.List;

/**
 * DTO para la respuesta REST de un resumen de conversación.
 */
public record ChatSummaryResponse(
        String id,
        String projectId,
        long seq,
        long fromSeq,
        long toSeq,
        int totalMessages,
        List<String> decisions,
        List<String> openQuestions,
        String plainText,
        Instant createdAt) {}

