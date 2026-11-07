package com.modelcollab.assistant.core;

/** Eventos que viajan por {@code /topic/projects/{projectId}/chat}. Contrato de cable en inglés. */
public final class ChatEvents {

    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";
    public static final String AI_TYPING = "AI_TYPING";

    private ChatEvents() {}

    public static String topicOf(String projectId) {
        return "/topic/projects/" + projectId + "/chat";
    }

    public record ChatMessageEvent(String event, ChatMessage message) {
        public static ChatMessageEvent of(ChatMessage message) {
            return new ChatMessageEvent(CHAT_MESSAGE, message);
        }
    }

    public record AiTypingEvent(
            String event, String forUserId, String forUserName, boolean active) {
        public static AiTypingEvent of(String forUserId, String forUserName, boolean active) {
            return new AiTypingEvent(AI_TYPING, forUserId, forUserName, active);
        }
    }
}
