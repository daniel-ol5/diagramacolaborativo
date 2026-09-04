package com.modelcollab.diagram.repository;

import com.modelcollab.diagram.model.Diagram;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagramRepository extends JpaRepository<Diagram, UUID> {

    List<Diagram> findByProjectId(UUID projectId);
}
