package com.modelcollab.project.repository;

import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectMemberId;
import com.modelcollab.project.model.ProjectRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    List<ProjectMember> findByIdProjectId(UUID projectId);

    Optional<ProjectMember> findByIdProjectIdAndIdUserId(UUID projectId, UUID userId);

    /**
     * Rol de un usuario sobre un diagrama, saltando el proyecto en una sola consulta.
     *
     * <p>Lo usa el {@code StompChannelInterceptor} para decidir si una mutación pasa. Va en una
     * consulta y no en dos (diagrama -> proyecto, proyecto -> miembro) porque es la ruta
     * caliente de la colaboración: se ejecuta al primer mensaje de cada sesión y el interceptor
     * cachea el resultado, pero aun así no hay motivo para pagar dos viajes a la base.
     *
     * <p>Un resultado vacío significa "el diagrama no existe" <b>o</b> "el usuario no es
     * miembro del proyecto", indistinguibles a propósito: quien no pertenece al proyecto
     * tampoco debería poder averiguar qué ids de diagrama existen.
     */
    @Query(
            """
            select pm.role
            from Diagram d
            join ProjectMember pm on pm.id.projectId = d.project.id
            where d.id = :diagramId and pm.id.userId = :userId
            """)
    Optional<ProjectRole> findRoleByDiagramAndUser(
            @Param("diagramId") UUID diagramId, @Param("userId") UUID userId);
}
