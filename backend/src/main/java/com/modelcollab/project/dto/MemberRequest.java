package com.modelcollab.project.dto;

import com.modelcollab.project.model.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de {@code POST /api/v1/projects/{pId}/members}: agregar a alguien ya registrado. */
public record MemberRequest(
        @NotBlank @Email String email,
        @NotNull ProjectRole role) {}
