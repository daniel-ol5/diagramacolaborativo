package com.modelcollab.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de {@code POST /api/v1/auth/login}. Contrato congelado (D-11).
 *
 * <p>Sin {@code @Email} ni {@code @Size} a propósito, al revés que en el registro: validar el
 * formato acá haría que un email mal escrito diera 400 y una contraseña incorrecta diera 401,
 * y esa diferencia le dice a un atacante qué parte acertó. El login responde 401 y nada más.
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
