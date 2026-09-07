package com.modelcollab.project.dto;

import com.modelcollab.project.model.ProjectInvitation;
import com.modelcollab.project.model.ProjectRole;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un enlace de invitación, tal como lo ve el dueño del proyecto.
 *
 * <p>{@code url} viaja armada para que el cliente no tenga que saber la forma de la ruta de
 * aceptación: si mañana cambia, cambia en un solo lado.
 */
public record InvitationResponse(
        UUID id,
        String token,
        String url,
        ProjectRole role,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        boolean active) {

    public static InvitationResponse de(
            ProjectInvitation invitacion, String base, OffsetDateTime ahora) {
        return new InvitationResponse(
                invitacion.getId(),
                invitacion.getToken(),
                base + "/invitations/" + invitacion.getToken(),
                invitacion.getRole(),
                invitacion.getCreatedAt(),
                invitacion.getExpiresAt(),
                invitacion.getRevokedAt(),
                invitacion.estaVigente(ahora));
    }
}
