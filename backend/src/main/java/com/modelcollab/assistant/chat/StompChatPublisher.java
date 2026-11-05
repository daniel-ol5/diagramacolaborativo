package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatEvents;
import com.modelcollab.assistant.core.ChatEvents.AiTypingEvent;
import com.modelcollab.assistant.core.ChatEvents.ChatMessageEvent;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** {@link ChatPublisher} sobre el broker STOMP en memoria. */
@Component
public class StompChatPublisher implements ChatPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public StompChatPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishMessage(ChatMessage message) {
        messagingTemplate.convertAndSend(
                ChatEvents.topicOf(message.projectId()), ChatMessageEvent.of(message));
    }

    @Override
    public void publishTyping(
            String projectId, String forUserId, String forUserName, boolean active) {
        messagingTemplate.convertAndSend(
                ChatEvents.topicOf(projectId), AiTypingEvent.of(forUserId, forUserName, active));
    }
}
