package com.modelcollab.assistant.ai;

/**
 * El modelo del chat no pudo responder. Es la única excepción que sale de un {@link
 * AiChatModelClient}: cualquier fallo del proveedor (red, timeout, clave inválida, saturación,
 * cuota, respuesta inutilizable) se traduce a ésta, como CU16 hace con la suya.
 *
 * <p>{@link Reason#PROVIDER_QUOTA} es el 429 <b>del proveedor</b> (se agotó el cupo de la cuenta
 * de IA). No tiene nada que ver con {@code RATE_LIMITED} del chat, que es el límite de mensajes
 * por persona que aplica {@code ChatService}.
 */
public class AiUnavailableException extends RuntimeException {

    public enum Reason {
        /** El proveedor no contestó: red, timeout, 5xx, 401/403 por clave inválida... */
        UNAVAILABLE,
        /** El proveedor devolvió 429: cuota de la cuenta de IA agotada. */
        PROVIDER_QUOTA,
        /** Contestó, pero sin texto utilizable. */
        INVALID_RESPONSE
    }

    private final Reason reason;

    public AiUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public AiUnavailableException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
