package com.modelcollab.assistant.summary;

import java.util.List;

/**
 * Datos estructurados de un resumen del chat (CU24).
 *
 * @param fromSeq número de secuencia del primer mensaje incluido en el resumen.
 * @param toSeq número de secuencia del último mensaje incluido en el resumen.
 * @param totalMessages total de mensajes condensados.
 * @param decisions decisiones y acuerdos tomados por el equipo sobre diseño y arquitectura.
 * @param openQuestions preguntas abiertas, dudas pendientes o temas sin resolver.
 * @param plainText representación en texto plano para clientes simples (como el móvil).
 */
public record ChatSummaryData(
        long fromSeq,
        long toSeq,
        int totalMessages,
        List<String> decisions,
        List<String> openQuestions,
        String plainText) {}

