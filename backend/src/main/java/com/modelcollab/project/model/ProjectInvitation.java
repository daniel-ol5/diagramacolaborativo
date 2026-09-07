package com.modelcollab.project.model;

import com.modelcollab.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * Enlace de invitación a un proyecto (tabla {@code project_invitations}, migración V3).
 *
 * <p>Existe como fila y no como token firmado porque <b>un enlace que no se puede revocar es un
 * problema sin arreglo</b>: los enlaces para compartir se filtran, y con una firma válida el
 * proyecto quedaría abierto para siempre.
 */
@Entity
@Table(name = "project_invitations")
public class ProjectInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ProjectRole role;

    /** Queda en null si se borra la cuenta de quien invitó; el enlace sigue siendo válido. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Null = no vence. */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** Null = vigente. Se marca en vez de borrar, para que quede rastro de que existió. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected ProjectInvitation() {
        // requerido por JPA
    }

    public ProjectInvitation(
            Project project, String token, ProjectRole role, User createdBy,
            OffsetDateTime expiresAt) {
        this.project = project;
        this.token = token;
        this.role = role;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void alPersistir() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    /** Vigente: ni revocada ni vencida. */
    public boolean estaVigente(OffsetDateTime ahora) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(ahora));
    }

    public void revocar(OffsetDateTime cuando) {
        if (revokedAt == null) {
            revokedAt = cuando;
        }
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getToken() {
        return token;
    }

    public ProjectRole getRole() {
        return role;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }
}
