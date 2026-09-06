package com.modelcollab.diagram.repository;

import com.modelcollab.diagram.model.DiagramOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagramOperationRepository extends JpaRepository<DiagramOperation, UUID> {

    List<DiagramOperation> findByDiagramIdOrderBySequenceNumAsc(UUID diagramId);

    List<DiagramOperation> findByDiagramIdAndSequenceNumGreaterThanOrderBySequenceNumAsc(
            UUID diagramId, long desdeSecuencia);

    /**
     * Busca una operación ya aplicada por el identificador que le puso el cliente, dentro de un
     * diagrama.
     *
     * <p>Va acotada por diagrama y no global: el {@code clientMutationId} lo genera el cliente
     * para identificar SU operación dentro de UN diagrama, y nadie puede garantizar que sea
     * único en todo el sistema. Con la búsqueda global, dos diagramas que usaran el mismo
     * identificador hacían estallar la consulta, que espera un único resultado.
     */
    Optional<DiagramOperation> findByDiagramIdAndClientMutationId(
            UUID diagramId, String clientMutationId);
}
