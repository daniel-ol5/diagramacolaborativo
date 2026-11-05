package com.modelcollab.assistant.core;

/**
 * Motivos de rechazo de un mensaje del chat. El nombre es el {@code code} de {@code
 * StompErrorMessage} en {@code /user/queue/errors}; el número es el status HTTP en REST y el
 * {@code status} del error STOMP, para que los dos canales digan lo mismo.
 */
public enum ChatErrorCode {
    NOT_PROJECT_MEMBER(403),
    INVALID_PAYLOAD(400),
    RATE_LIMITED(429);

    private final int status;

    ChatErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
