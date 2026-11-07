package com.modelcollab.assistant.core;

/**
 * Un mensaje del chat que no se acepta. La lanza {@code ChatService} y la traduce cada entrada:
 * STOMP a {@code /user/queue/errors}, REST a un status HTTP. El mensaje es para el usuario.
 */
public class ChatRejectedException extends RuntimeException {

    private final ChatErrorCode code;

    public ChatRejectedException(ChatErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ChatErrorCode code() {
        return code;
    }
}
