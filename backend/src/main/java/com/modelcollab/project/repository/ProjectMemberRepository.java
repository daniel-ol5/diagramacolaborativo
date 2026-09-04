package com.modelcollab.project.repository;

import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectMemberId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    List<ProjectMember> findByIdProjectId(UUID projectId);

    Optional<ProjectMember> findByIdProjectIdAndIdUserId(UUID projectId, UUID userId);
}
