package com.modelcollab.assistant.ai;

/**
 * Respuesta del modelo del chat.
 *
 * @param text el texto tal cual lo devolvió el modelo.
 * @param model el modelo que efectivamente respondió (puede ser el de respaldo).
 */
public record ChatCompletion(String text, String model) {}
