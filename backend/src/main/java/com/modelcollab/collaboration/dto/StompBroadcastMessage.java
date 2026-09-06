package com.modelcollab.collaboration.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Lo que el servidor difunde a {@code /topic/diagrams/{diagramId}}: el mensaje del emisor más
 * los dos datos que sólo el servidor puede afirmar.
 *
 * <p>{@code userId} sale del Principal de la sesión WebSocket y <b>nunca</b> del cuerpo del
 * mensaje: si se aceptara del cliente, cualquiera podría firmar una mutación con el id de otro.
 *
 * <p>{@code serverTimestamp} es del reloj del servidor por la misma razón — es el árbitro del
 * orden y de la resolución LWW, y los relojes de veinte navegadores no están sincronizados.
 *
 * <p>{@code clientMutationId} viaja de vuelta sin tocar para el filtrado de eco de §8.3.
 */
public record StompBroadcastMessage(
        String op,
        String targetId,
        String clientMutationId,
        Map<String, Object> payload,
        String userId,
        Instant serverTimestamp) {

    public static StompBroadcastMessage de(
            StompMutationMessage mensaje, String userId, Instant serverTimestamp) {
        return new StompBroadcastMessage(
                mensaje.op(),
                mensaje.targetId(),
                mensaje.clientMutationId(),
                mensaje.payload(),
                userId,
                serverTimestamp);
    }
}
