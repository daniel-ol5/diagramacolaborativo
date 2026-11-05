package com.modelcollab.assistant.chat;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link ChatParticipants} sobre los repositorios existentes de PostgreSQL. */
@Component
public class JpaChatParticipants implements ChatParticipants {

    private final ProjectMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final DiagramRepository diagramRepository;

    public JpaChatParticipants(
            ProjectMemberRepository memberRepository,
            UserRepository userRepository,
            DiagramRepository diagramRepository) {
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.diagramRepository = diagramRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectRole> roleIn(UUID projectId, UUID userId) {
        return memberRepository
                .findByIdProjectIdAndIdUserId(projectId, userId)
                .map(ProjectMember::getRole);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> fullName(UUID userId) {
        return userRepository.findById(userId).map(User::getFullName);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean diagramBelongsToProject(UUID diagramId, UUID projectId) {
        return diagramRepository.existsByIdAndProjectId(diagramId, projectId);
    }
}
