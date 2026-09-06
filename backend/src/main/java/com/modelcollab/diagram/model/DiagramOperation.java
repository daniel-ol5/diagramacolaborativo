package com.modelcollab.diagram.model;

import com.modelcollab.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Log append-only de operaciones colaborativas (tabla {@code diagram_operations}, §13).
 * Sirve para auditoría, historial y para la idempotencia del rebase offline a través
 * de {@code clientMutationId}.
 */
@Entity
@Table(name = "diagram_operations")
public class DiagramOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "diagram_id", nullable = false)
    private Diagram diagram;

    /** Queda en null cuando el autor fue eliminado (ON DELETE SET NULL). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    /** Elemento sobre el que actúa; null en las operaciones contra la raíz del diagrama. */
    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "client_mutation_id", length = 100)
    private String clientMutationId;

    @Column(name = "sequence_num", nullable = false)
    private long sequenceNum;

    @Column(name = "server_timestamp")
    private OffsetDateTime serverTimestamp;

    protected DiagramOperation() {
        // requerido por JPA
    }

    public DiagramOperation(Diagram diagram, User user, String operationType, String targetId,
            String payload, String clientMutationId, long sequenceNum) {
        this.diagram = diagram;
        this.user = user;
        this.operationType = operationType;
        this.targetId = targetId;
        this.payload = payload;
        this.clientMutationId = clientMutationId;
        this.sequenceNum = sequenceNum;
    }

    @PrePersist
    void alPersistir() {
        if (serverTimestamp == null) {
            serverTimestamp = OffsetDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Diagram getDiagram() {
        return diagram;
    }

    public User getUser() {
        return user;
    }

    public String getOperationType() {
        return operationType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getPayload() {
        return payload;
    }

    public String getClientMutationId() {
        return clientMutationId;
    }

    public long getSequenceNum() {
        return sequenceNum;
    }

    public OffsetDateTime getServerTimestamp() {
        return serverTimestamp;
    }
}
