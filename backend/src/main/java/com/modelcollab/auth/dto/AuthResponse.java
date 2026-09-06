package com.modelcollab.auth.dto;

import com.modelcollab.auth.model.User;
import java.util.UUID;

/**
 * Respuesta de login y de registro. Contrato congelado (D-11): los dos endpoints devuelven
 * exactamente este cuerpo, para que el cliente tenga un solo camino de manejo.
 *
 * <p>{@code tokenType} viaja explícito aunque hoy sea siempre "Bearer": es lo que el cliente
 * concatena para armar el header {@code Authorization}, y dejarlo hardcodeado del lado del
 * cliente es la clase de suposición que rompe el día que se agrega un segundo esquema.
 */
public record AuthResponse(
        String token, String tokenType, UUID userId, String email, String fullName) {

    private static final String BEARER = "Bearer";

    public static AuthResponse de(String token, User user) {
        return new AuthResponse(token, BEARER, user.getId(), user.getEmail(), user.getFullName());
    }
}
