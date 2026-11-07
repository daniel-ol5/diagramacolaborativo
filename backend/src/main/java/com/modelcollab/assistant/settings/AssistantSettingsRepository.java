package com.modelcollab.assistant.settings;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AssistantSettingsRepository extends JpaRepository<AssistantSettingsEntity, UUID> {
}

