package com.modelcollab.assistant.settings;

import com.modelcollab.assistant.ai.AssistantLevel;
import com.modelcollab.assistant.ai.AssistantMode;
import com.modelcollab.assistant.ai.AssistantProvider;
import com.modelcollab.assistant.ai.AssistantSettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "assistant_settings")
public class AssistantSettingsEntity {

    @Id
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private AssistantMode mode = AssistantMode.ON_MENTION;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 30)
    private AssistantLevel level = AssistantLevel.INTERMEDIATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private AssistantProvider provider = AssistantProvider.CLOUD;

    @Column(name = "local_endpoint", length = 500)
    private String localEndpoint;

    @Column(name = "max_tokens")
    private Integer maxTokens = AssistantSettings.DEFAULT_MAX_TOKENS;

    @Column(name = "summary_frequency")
    private Integer summaryFrequency = AssistantSettings.DEFAULT_SUMMARY_FREQUENCY;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by_id")
    private UUID updatedById;

    public AssistantSettingsEntity() {}

    public AssistantSettingsEntity(UUID projectId) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
    }

    public AssistantSettings toDomain() {
        return new AssistantSettings(
                mode != null ? mode : AssistantMode.ON_MENTION,
                level != null ? level : AssistantLevel.INTERMEDIATE,
                provider != null ? provider : AssistantProvider.CLOUD,
                localEndpoint,
                maxTokens != null ? maxTokens : AssistantSettings.DEFAULT_MAX_TOKENS,
                summaryFrequency != null ? summaryFrequency : AssistantSettings.DEFAULT_SUMMARY_FREQUENCY);
    }

    public void updateFrom(AssistantSettings settings, UUID updatedBy) {
        if (settings != null) {
            this.mode = settings.mode();
            this.level = settings.level();
            this.provider = settings.provider();
            this.localEndpoint = settings.localEndpoint();
            this.maxTokens = settings.maxTokens();
            this.summaryFrequency = settings.summaryFrequency();
        }
        this.updatedById = updatedBy;
        this.updatedAt = Instant.now();
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public AssistantMode getMode() {
        return mode;
    }

    public void setMode(AssistantMode mode) {
        this.mode = mode;
    }

    public AssistantLevel getLevel() {
        return level;
    }

    public void setLevel(AssistantLevel level) {
        this.level = level;
    }

    public AssistantProvider getProvider() {
        return provider;
    }

    public void setProvider(AssistantProvider provider) {
        this.provider = provider;
    }

    public String getLocalEndpoint() {
        return localEndpoint;
    }

    public void setLocalEndpoint(String localEndpoint) {
        this.localEndpoint = localEndpoint;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Integer getSummaryFrequency() {
        return summaryFrequency;
    }

    public void setSummaryFrequency(Integer summaryFrequency) {
        this.summaryFrequency = summaryFrequency;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getUpdatedById() {
        return updatedById;
    }

    public void setUpdatedById(UUID updatedById) {
        this.updatedById = updatedById;
    }
}

