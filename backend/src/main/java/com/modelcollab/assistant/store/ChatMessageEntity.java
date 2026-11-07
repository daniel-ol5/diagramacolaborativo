package com.modelcollab.assistant.store;

import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessageKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fila de {@code chat_messages} (V4). Es el lado de persistencia del {@code ChatMessage} del
 * dominio; la traducción entre los dos está en {@link JpaChatStore}, a mano y en un solo lugar.
 *
 * <p>Las referencias a proyecto, diagrama y mensaje respondido son UUIDs sueltos y no relaciones
 * JPA: el chat nunca navega hacia el proyecto ni hacia el diagrama, y una relación sólo
 * agregaría consultas perezosas. La integridad la dan las FK de la migración.
 *
 * <p>El id lo asigna el dominio (no hay {@code @GeneratedValue}), por eso {@link JpaChatStore}
 * inserta con {@code persist} y no con {@code merge}.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "diagram_id")
    private UUID diagramId;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 10)
    private ChatAuthorType authorType;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;

    @Column(name = "author_color", length = 16)
    private String authorColor;

    @Column(name = "addressed_to_id")
    private UUID addressedToId;

    @Column(name = "addressed_to_name", length = 150)
    private String addressedToName;

    @Column(name = "reply_to_id")
    private UUID replyToId;

    @Column(name = "client_message_id", length = 100)
    private String clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private ChatMessageKind kind;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /** JSON ya serializado; ver {@link JpaChatStore} sobre por qué no el ObjectMapper canónico. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta")
    private String meta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChatMessageEntity() {
        // requerido por JPA
    }

    ChatMessageEntity(
            UUID id,
            UUID projectId,
            UUID diagramId,
            long seq,
            ChatAuthorType authorType,
            UUID authorId,
            String authorName,
            String authorColor,
            UUID addressedToId,
            String addressedToName,
            UUID replyToId,
            String clientMessageId,
            ChatMessageKind kind,
            String text,
            String meta,
            OffsetDateTime createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.diagramId = diagramId;
        this.seq = seq;
        this.authorType = authorType;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorColor = authorColor;
        this.addressedToId = addressedToId;
        this.addressedToName = addressedToName;
        this.replyToId = replyToId;
        this.clientMessageId = clientMessageId;
        this.kind = kind;
        this.text = text;
        this.meta = meta;
        this.createdAt = createdAt;
    }

    UUID getId() { return id; }
    UUID getProjectId() { return projectId; }
    UUID getDiagramId() { return diagramId; }
    long getSeq() { return seq; }
    ChatAuthorType getAuthorType() { return authorType; }
    UUID getAuthorId() { return authorId; }
    String getAuthorName() { return authorName; }
    String getAuthorColor() { return authorColor; }
    UUID getAddressedToId() { return addressedToId; }
    String getAddressedToName() { return addressedToName; }
    UUID getReplyToId() { return replyToId; }
    String getClientMessageId() { return clientMessageId; }
    ChatMessageKind getKind() { return kind; }
    String getText() { return text; }
    String getMeta() { return meta; }
    OffsetDateTime getCreatedAt() { return createdAt; }
}
