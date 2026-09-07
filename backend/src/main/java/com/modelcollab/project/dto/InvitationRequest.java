package com.modelcollab.project.dto;

import com.modelcollab.project.model.ProjectRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Cuerpo de {@code POST /api/v1/projects/{pId}/invitations}.
 *
 * <p>{@code expiresInDays} es opcional: un enlace sin vencimiento es un caso legítimo —el dueño
 * lo revoca cuando quiere— y obligar a poner una fecha llevaría a que todos pongan un año.
 */
public record InvitationRequest(
        @NotNull ProjectRole role,
        @Positive @Max(365) Integer expiresInDays) {}
