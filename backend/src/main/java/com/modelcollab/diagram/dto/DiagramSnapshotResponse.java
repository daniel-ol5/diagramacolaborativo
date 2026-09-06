package com.modelcollab.diagram.dto;

import com.modelcollab.metamodel.model.CanonicalModel;
import java.util.UUID;

/**
 * Respuesta de {@code GET /api/v1/diagrams/{id}/snapshot} (§14): el estado canónico actual.
 *
 * <p>{@code mutationVersion} viaja también fuera del modelo, al mismo nivel que el id, porque es
 * lo que el cliente tiene que guardar para después pedir "todo lo que pasó desde N" y para
 * decidir si un mensaje que le llega por STOMP es nuevo o es un eco de algo que ya tiene.
 */
public record DiagramSnapshotResponse(
        UUID id, String name, long mutationVersion, CanonicalModel currentState) {}
