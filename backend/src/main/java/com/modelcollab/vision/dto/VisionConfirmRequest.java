package com.modelcollab.vision.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Lo que manda el navegador cuando la persona aprieta "confirmar" en el modal.
 *
 * <p>{@code clientMutationId} sirve para lo mismo de siempre: si la respuesta se pierde y alguien
 * vuelve a confirmar, el diagrama no recibe la foto dos veces.
 */
public record VisionConfirmRequest(@NotNull VisionDraft draft, String clientMutationId) {}
