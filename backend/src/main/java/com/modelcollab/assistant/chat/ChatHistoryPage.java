package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatMessage;
import java.util.List;

/**
 * Una página del historial del chat, <b>siempre ascendente por seq</b>.
 *
 * @param hasMore si hay más mensajes en la dirección que se pidió: más viejos para "los últimos"
 *     y para {@code beforeSeq}; más nuevos para {@code afterSeq}.
 */
public record ChatHistoryPage(List<ChatMessage> messages, boolean hasMore) {

    public ChatHistoryPage {
        messages = List.copyOf(messages);
    }
}
