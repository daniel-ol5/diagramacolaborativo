package com.modelcollab.diagram.dto;

import com.modelcollab.diagram.model.Diagram;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un diagrama en un listado. <b>Sin</b> el {@code current_state}: la lista de diagramas de un
 * proyecto no tiene por qué arrastrar el JSONB completo de cada uno, que puede pesar cientos de
 * kilobytes. Para el contenido está {@code GET /api/v1/diagrams/{id}/snapshot}.
 */
public record DiagramResponse(
        UUID id, UUID projectId, String name, long mutationVersion, OffsetDateTime updatedAt) {

    public static DiagramResponse de(Diagram diagrama) {
        return new DiagramResponse(
                diagrama.getId(),
                diagrama.getProject().getId(),
                diagrama.getName(),
                diagrama.getMutationVersion(),
                diagrama.getUpdatedAt());
    }
}
