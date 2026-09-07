package com.modelcollab.project.dto;

import com.modelcollab.project.model.ProjectRole;

/**
 * Lo que se ve al abrir un enlace, <b>antes</b> de aceptarlo: "te invitaron a X como EDITOR".
 *
 * <p>Este endpoint <b>no agrega a nadie</b>. Aceptar tiene que ser un acto deliberado: si sólo
 * abrir el enlace ya diera acceso, reenviarlo por error metería gente en el proyecto sin que
 * nadie lo decidiera.
 *
 * <p>{@code reason} distingue un enlace vencido de uno revocado o inexistente, para que el
 * cliente pueda decir algo útil. Pero cuando el token no es válido, {@code projectName} viene en
 * null: si dijera el nombre del proyecto, un token inventado se convertiría en una forma de
 * averiguar qué proyectos existen.
 */
public record InvitationPreview(
        boolean valid, String reason, String projectName, ProjectRole role) {

    public static final String VENCIDA = "EXPIRED";
    public static final String REVOCADA = "REVOKED";
    public static final String INEXISTENTE = "NOT_FOUND";

    public static InvitationPreview valida(String projectName, ProjectRole role) {
        return new InvitationPreview(true, null, projectName, role);
    }

    public static InvitationPreview invalida(String reason) {
        return new InvitationPreview(false, reason, null, null);
    }
}
