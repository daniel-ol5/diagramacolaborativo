package com.modelcollab.assistant.core;

/**
 * Difusión del chat a la sala del proyecto, {@code /topic/projects/{projectId}/chat}. Se llama
 * <b>después</b> de persistir: lo que un cliente ve en vivo tiene que poder recuperarse luego del
 * historial.
 */
public interface ChatPublisher {

    /** {@code {event:"CHAT_MESSAGE", message:{...}}}. */
    void publishMessage(ChatMessage message);

    /** {@code {event:"AI_TYPING", forUserId, forUserName, active}}. */
    void publishTyping(String projectId, String forUserId, String forUserName, boolean active);
}
