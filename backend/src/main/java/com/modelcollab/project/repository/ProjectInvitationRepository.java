package com.modelcollab.project.repository;

import com.modelcollab.project.model.ProjectInvitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, UUID> {

    /**
     * Busca por token trayendo el proyecto de una vez: quien abre un enlace necesita el nombre
     * del proyecto para poder decidir si acepta.
     */
    @Query("select i from ProjectInvitation i join fetch i.project where i.token = :token")
    Optional<ProjectInvitation> findPorToken(@Param("token") String token);

    List<ProjectInvitation> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
