package com.modelcollab.collaboration.interceptor;

import java.security.Principal;
import java.util.UUID;

/**
 * Identidad de una sesión WebSocket, establecida en el CONNECT a partir del JWT.
 *
 * <p>{@code getName()} devuelve el {@code userId} en texto, y ese detalle es funcional, no
 * cosmético: Spring usa el nombre del Principal para resolver los destinos {@code /user/**}, así
 * que es lo que hace que {@code convertAndSendToUser(userId, "/queue/errors", …)} llegue a la
 * sesión correcta.
 */
public record StompPrincipal(UUID userId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
