package com.modelcollab.project.dto;

import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import java.util.UUID;

/**
 * Un colaborador del proyecto, para la pantalla de "quién tiene acceso".
 *
 * <p>Lleva el {@code email} además del nombre porque es lo que distingue a dos personas que se
 * llaman igual, y porque es el dato con el que se agrega a alguien por
 * {@code POST /projects/{id}/members}.
 */
public record MemberResponse(UUID userId, String email, String fullName, ProjectRole role) {

    public static MemberResponse de(ProjectMember miembro) {
        return new MemberResponse(
                miembro.getUser().getId(),
                miembro.getUser().getEmail(),
                miembro.getUser().getFullName(),
                miembro.getRole());
    }
}
