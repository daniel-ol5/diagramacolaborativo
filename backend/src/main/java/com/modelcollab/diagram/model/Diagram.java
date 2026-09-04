package com.modelcollab.diagram.model;

import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.project.model.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Diagrama de clases (tabla {@code diagrams}, §13). La columna {@code current_state}
 * guarda el modelo canónico de §7 como JSONB; se mantiene como String para que la
 * única conversión a {@link CanonicalModel} pase por el ObjectMapper canónico.
 */
@Entity
@Table(name = "diagrams")
public class Diagram {

    public static final String ESTADO_INICIAL =
            "{\"schemaVersion\": \"1.0.0\", \"mutationVersion\": 1, "
                    + "\"packages\": [], \"classes\": [], \"relationships\": []}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "schema_version", nullable = false, length = 20)
    private String schemaVersion = CanonicalModel.CURRENT_SCHEMA_VERSION;

    @Column(name = "mutation_version", nullable = false)
    private long mutationVersion = 1L;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_state", nullable = false)
    private String currentState = ESTADO_INICIAL;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Diagram() {
        // requerido por JPA
    }

    public Diagram(Project project, String name) {
        this.project = project;
        this.name = name;
    }

    @PrePersist
    @PreUpdate
    void alGuardar() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public long getMutationVersion() {
        return mutationVersion;
    }

    public void setMutationVersion(long mutationVersion) {
        this.mutationVersion = mutationVersion;
    }

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
