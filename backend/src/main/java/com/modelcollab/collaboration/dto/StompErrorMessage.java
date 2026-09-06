package com.modelcollab.collaboration.dto;

/**
 * Error dirigido a un solo cliente por su canal privado {@code /user/queue/errors}. Contrato
 * congelado (D-11).
 *
 * <p>Va al canal privado y no a {@code /topic}: que un VIEWER intente mutar no es información
 * que le importe a los otros diecinueve colaboradores de la sala.
 *
 * <p>{@code status} y {@code code} se mandan los dos a propósito: el número le da al cliente la
 * misma semántica que ya conoce de REST, y el código simbólico es lo que puede ramificar sin
 * atarse a que {@code FORBIDDEN_ROLE} y un futuro {@code FORBIDDEN_LOCK} compartan el 403.
 */
public record StompErrorMessage(
        int status, String code, String destination, String clientMutationId, String message) {

    public static final String FORBIDDEN_ROLE = "FORBIDDEN_ROLE";

    public static StompErrorMessage rolInsuficiente(
            String destination, String clientMutationId, String message) {
        return new StompErrorMessage(403, FORBIDDEN_ROLE, destination, clientMutationId, message);
    }
}
