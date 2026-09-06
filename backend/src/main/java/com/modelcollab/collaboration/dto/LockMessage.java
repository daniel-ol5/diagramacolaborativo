package com.modelcollab.collaboration.dto;

/**
 * Lo que un cliente envía a {@code /app/diagram/{diagramId}/lock} (§8.2 punto 1).
 *
 * <p>{@code userId} no viaja acá aunque §8.2 lo dibuje en el ejemplo: el servidor lo toma del
 * Principal de la sesión. Aceptarlo del cliente permitiría pedir un lock en nombre de otro.
 */
public record LockMessage(String action, String targetId) {

    public static final String ACQUIRE = "ACQUIRE";
    public static final String RELEASE = "RELEASE";
    /** Renovación del TTL que el cliente manda cada 2000 ms mientras sigue editando (§8.2.4). */
    public static final String HEARTBEAT = "HEARTBEAT";
}
