package com.modelcollab.assistant.ai;

import java.util.Objects;

/**
 * Un turno de la conversación que se le pasa al modelo. El texto de una persona llega como
 * {@code USER} y ya etiquetado ("[Ana]: ..."); lo que dijo la IA antes, como {@code ASSISTANT}.
 */
public record ChatTurn(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public ChatTurn {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }

    public static ChatTurn user(String content) {
        return new ChatTurn(Role.USER, content);
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn(Role.ASSISTANT, content);
    }
}
