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

    Optional<DiagramOperation> findByClientMutationId(String clientMutationId);
}
