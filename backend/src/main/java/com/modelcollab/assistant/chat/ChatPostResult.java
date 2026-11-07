package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatMessage;

/**
 * Resultado de escribir en el chat.
 *
 * @param duplicate {@code true} si el clientMessageId ya existía: se devuelve el mensaje original
 *     y no se volvió a difundir. REST lo traduce a 200 en vez de 201.
 */
public record ChatPostResult(ChatMessage message, boolean duplicate) {}
