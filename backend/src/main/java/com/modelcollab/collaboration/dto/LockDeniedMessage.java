package com.modelcollab.collaboration.dto;

/**
 * Denegación de lock, dirigida <b>sólo al solicitante</b> por {@code /user/queue/locks}
 * (§8.2 punto 5).
 *
 * <p>Canal distinto del {@code /user/queue/errors} del rechazo por rol, y a propósito: que un
 * elemento esté tomado es <b>operación normal</b>, no un fallo. El cliente lo muestra como un
 * aviso discreto ("Elemento en edición por Usuario B") y deja el nodo en modo lectura; si viniera
 * por el canal de errores, la interfaz lo trataría como un problema y le enseñaría al usuario a
 * desconfiar de una herramienta que funciona bien.
 *
 * <p>{@code expiresInMs} es lo que convierte la denegación en información accionable: el cliente
 * sabe en cuánto puede reintentar. <b>No hay cola de espera</b> — el fallo rápido es lo que
 * garantiza ausencia de interbloqueos e inanición (§8.2 punto 5).
 */
public record LockDeniedMessage(
        String event, String targetId, String heldBy, long expiresInMs) {

    public static final String DENIED = "LOCK_DENIED";

    public static LockDeniedMessage de(String targetId, String heldBy, long expiresInMs) {
        return new LockDeniedMessage(DENIED, targetId, heldBy, expiresInMs);
    }
}
