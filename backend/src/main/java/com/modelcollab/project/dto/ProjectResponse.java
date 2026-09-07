package com.modelcollab.project.dto;

import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectRole;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un proyecto tal como lo ve <b>quien pregunta</b>.
 *
 * <p>{@code role} es el rol del solicitante en este proyecto, no un atributo del proyecto. Va acá
 * porque es lo que decide qué puede hacer la interfaz: sin él, el cliente tendría que pedir la
 * lista de miembros de cada proyecto sólo para saber si dibuja el botón de compartir.
 */
public record ProjectResponse(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        ProjectRole role,
        OffsetDateTime createdAt) {

    public static ProjectResponse de(Project proyecto, ProjectRole miRol) {
        return new ProjectResponse(
                proyecto.getId(),
                proyecto.getName(),
                proyecto.getDescription(),
                proyecto.getOwner().getId(),
                miRol,
                proyecto.getCreatedAt());
    }
}
