package com.modelcollab.assistant.review;

/**
 * Un problema específico reportado durante la revisión del diagrama.
 *
 * @param severity severidad (ERROR bloqueante, WARNING advertencia, SUGGESTION sugerencia).
 * @param code código identificador de la regla violada.
 * @param elementId id del elemento en el lienzo (para resaltarlo o colocar insignia).
 * @param elementName nombre legible de la clase o elemento afectado.
 * @param message descripción pedagógica y comprensible del problema.
 */
public record ReviewIssue(
        ReviewIssueSeverity severity,
        String code,
        String elementId,
        String elementName,
        String message) {}

