package com.modelcollab.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /api/v1/projects}. */
public record ProjectRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description) {}
