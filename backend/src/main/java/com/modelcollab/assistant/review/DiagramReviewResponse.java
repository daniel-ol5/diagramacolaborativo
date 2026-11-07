package com.modelcollab.assistant.review;

import java.util.List;

/**
 * Respuesta enviada al cliente tras solicitar una revisión arquitectónica del diagrama (CU22).
 */
public record DiagramReviewResponse(
        int score,
        int totalErrors,
        int totalWarnings,
        int totalSuggestions,
        long diagramVersion,
        String mentorComment,
        List<ReviewIssue> issues) {}

