package com.modelcollab.diagram.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /api/v1/projects/{pId}/diagrams}. */
public record DiagramRequest(@NotBlank @Size(max = 150) String name) {}
