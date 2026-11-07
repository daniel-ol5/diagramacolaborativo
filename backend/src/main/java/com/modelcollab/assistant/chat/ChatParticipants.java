package com.modelcollab.assistant.chat;

import com.modelcollab.project.model.ProjectRole;
import java.util.Optional;
import java.util.UUID;

/**
 * Lo que el chat necesita saber del resto del sistema sobre las personas: si son miembros y cómo
 * se llaman. Es una interfaz para que {@link ChatService} se pruebe sin JPA ni base de datos; la
 * implementación real es {@link JpaChatParticipants}.
 */
public interface ChatParticipants {

    /** Rol del usuario en el proyecto; vacío si no es miembro. */
    Optional<ProjectRole> roleIn(UUID projectId, UUID userId);

    /** Nombre completo del usuario; vacío si la cuenta ya no existe. */
    Optional<String> fullName(UUID userId);

    /**
     * Si el diagrama existe y es de este proyecto. Un diagrama inexistente y uno de otro proyecto
     * responden igual ({@code false}) a propósito: no se revela qué ids existen.
     */
    boolean diagramBelongsToProject(UUID diagramId, UUID projectId);
}
