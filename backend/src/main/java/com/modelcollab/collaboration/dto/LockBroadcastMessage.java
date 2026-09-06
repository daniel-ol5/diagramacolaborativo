package com.modelcollab.collaboration.dto;

import java.util.UUID;

/**
 * Eventos de lock que se difunden a la sala entera por {@code /topic/diagrams/{diagramId}}
 * (§8.2 puntos 2 y 4). Los campos nulos no se serializan (el mapper canónico usa
 * {@code NON_NULL}), así que un {@code LOCK_RELEASED} viaja sólo con {@code event} y
 * {@code targetId}, tal como lo define §8.2.
 */
public record LockBroadcastMessage(
        String event, String targetId, UUID userId, String userName, String color, Long ttl) {

    public static final String ACQUIRED = "LOCK_ACQUIRED";
    public static final String RELEASED = "LOCK_RELEASED";

    public static LockBroadcastMessage adquirido(
            String targetId, Participante quien, long ttlMs) {
        return new LockBroadcastMessage(
                ACQUIRED, targetId, quien.userId(), quien.nombre(), quien.color(), ttlMs);
    }

    public static LockBroadcastMessage liberado(String targetId) {
        return new LockBroadcastMessage(RELEASED, targetId, null, null, null, null);
    }
}
