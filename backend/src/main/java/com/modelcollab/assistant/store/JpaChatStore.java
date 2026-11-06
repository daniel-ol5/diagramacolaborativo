package com.modelcollab.assistant.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modelcollab.assistant.chat.ChatHistoryQuery;
import com.modelcollab.assistant.chat.ChatHistoryStore;
import com.modelcollab.assistant.chat.ChatReadState;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChatStore} sobre PostgreSQL: tablas {@code chat_messages} y {@code chat_counters} de V4.
 * Es el almacenamiento por defecto ({@code modelcollab.chat.store=postgres}).
 *
 * <h2>nextSeq y save van en transacciones separadas</h2>
 *
 * <p>El puerto los expone por separado y {@code ChatService} los llama en secuencia, así que si
 * el INSERT del mensaje falla después de tomar el número, ese seq queda sin usar. Es un hueco, no
 * una repetición: el orden sigue siendo correcto y los clientes fusionan por seq. Unirlos en una
 * transacción exige cambiar {@code ChatService} o el puerto, y queda para la etapa 2.
 */
@Repository
@ConditionalOnProperty(
        name = "modelcollab.chat.store",
        havingValue = "postgres",
        matchIfMissing = true)
public class JpaChatStore implements ChatStore, ChatHistoryStore {

    /** Nombre del índice único parcial de V4; por él se reconoce un duplicado del cliente. */
    static final String INDICE_CLIENT_MESSAGE = "uq_chat_messages_client_message";

    /** Violación de unicidad en PostgreSQL. */
    private static final String SQLSTATE_UNIQUE = "23505";

    private static final String SIGUIENTE_SEQ =
            """
            INSERT INTO chat_counters (project_id, last_seq) VALUES (?, 1)
            ON CONFLICT (project_id) DO UPDATE SET last_seq = chat_counters.last_seq + 1
            RETURNING last_seq
            """;

    /**
     * CU23: estado de lectura en una sola consulta, para que lastReadSeq, lastSeq y unreadCount
     * salgan de la misma foto. Los parámetros van en orden de aparición: proyecto (lastSeq),
     * proyecto y usuario (unread), proyecto y usuario (lastReadSeq). Los mensajes con
     * {@code author_id} nulo (IA y sistema) cuentan como no leídos; {@code IS DISTINCT FROM} los
     * incluye sin un OR aparte.
     */
    static final String ESTADO_DE_LECTURA =
            """
            SELECT r.last_read_seq,
                   (SELECT COALESCE(MAX(m.seq), 0) FROM chat_messages m
                     WHERE m.project_id = ?) AS last_seq,
                   (SELECT COUNT(*) FROM chat_messages m
                     WHERE m.project_id = ? AND m.seq > r.last_read_seq
                       AND m.author_id IS DISTINCT FROM ?) AS unread
              FROM (SELECT COALESCE(
                       (SELECT s.last_read_seq FROM chat_read_state s
                         WHERE s.project_id = ? AND s.user_id = ?), 0) AS last_read_seq) r
            """;

    /**
     * CU23: marcar como leído en UNA sentencia atómica. El valor se recorta al mayor seq del
     * proyecto (no se marca un futuro) y solo avanza (GREATEST con lo que ya había). Parámetros:
     * proyecto, usuario, upToSeq, proyecto.
     */
    static final String MARCAR_LEIDO =
            """
            INSERT INTO chat_read_state (project_id, user_id, last_read_seq)
            SELECT ?, ?, LEAST(?, COALESCE(MAX(m.seq), 0)) FROM chat_messages m WHERE m.project_id = ?
            ON CONFLICT (project_id, user_id) DO UPDATE
               SET last_read_seq = GREATEST(chat_read_state.last_read_seq, EXCLUDED.last_read_seq),
                   updated_at = now()
            """;

    /**
     * Un ObjectMapper común para {@code meta}, y no el canónico: el canónico rechaza propiedades
     * desconocidas a propósito, para detectar desvíos del contrato de §7. {@code meta} es un
     * mapa libre de cada caso de uso; leerlo con reglas estrictas sólo podría hacer fallar la
     * lectura del historial por un campo que nadie valida.
     */
    private static final ObjectMapper JSON_META =
            JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private static final TypeReference<Map<String, Object>> TIPO_META = new TypeReference<>() {};

    @PersistenceContext private EntityManager em;

    private final JdbcTemplate jdbc;

    public JpaChatStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- escritura

    @Override
    @Transactional
    public ChatMessage save(ChatMessage message) {
        ChatMessageEntity fila = aFila(message);
        try {
            em.persist(fila);
            // flush acá y no al commit: la violación del índice único tiene que aparecer dentro
            // de este método para poder traducirla al contrato del puerto.
            em.flush();
        } catch (PersistenceException e) {
            if (esDuplicadoDeCliente(e)) {
                // La carrera entre dos reintentos que pasaron los dos el chequeo previo:
                // ChatService la trata como duplicado, no como error 500.
                throw new DuplicateKeyException(
                        "Mensaje duplicado: " + message.projectId() + "/"
                                + message.clientMessageId(),
                        e);
            }
            if (esViolacionDeUnicidad(e)) {
                // Cualquier otro 23505 —hoy, (project_id, seq)— es un error real. Se envuelve
                // acá porque, relanzado tal cual, el proxy de @Repository lo traduciría a
                // DuplicateKeyException (HibernateJpaDialect mapea así todo 23505) y el puerto
                // diría "duplicado" de algo que no lo es. DataIntegrityViolationException ya es
                // DataAccessException, así que el proxy la deja pasar sin volver a traducirla.
                throw new DataIntegrityViolationException(
                        "Violación de unicidad al guardar el mensaje " + message.id()
                                + " (seq " + message.seq() + ")",
                        e);
            }
            // El resto, FK violadas (23503) incluidas, sigue su traducción normal.
            throw e;
        }
        return aDominio(fila);
    }

    @Override
    @Transactional
    public long nextSeq(String projectId) {
        Long seq = jdbc.queryForObject(SIGUIENTE_SEQ, Long.class, UUID.fromString(projectId));
        if (seq == null) {
            throw new IllegalStateException("RETURNING last_seq no devolvió fila para " + projectId);
        }
        return seq;
    }

    // --------------------------------------------------------------- lectura

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatMessage> findByClientMessageId(String projectId, String clientMessageId) {
        UUID proyecto = uuidONulo(projectId);
        if (proyecto == null || clientMessageId == null) {
            return Optional.empty();
        }
        return em.createQuery(
                        "select m from ChatMessageEntity m"
                                + " where m.projectId = :p and m.clientMessageId = :c",
                        ChatMessageEntity.class)
                .setParameter("p", proyecto)
                .setParameter("c", clientMessageId)
                .getResultStream()
                .findFirst()
                .map(JpaChatStore::aDominio);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChatMessage> findById(String projectId, String id) {
        UUID proyecto = uuidONulo(projectId);
        UUID mensaje = uuidONulo(id);
        if (proyecto == null || mensaje == null) {
            // Un id que ni siquiera es UUID no existe; no es un error del servidor.
            return Optional.empty();
        }
        return Optional.ofNullable(em.find(ChatMessageEntity.class, mensaje))
                .filter(m -> m.getProjectId().equals(proyecto))
                .map(JpaChatStore::aDominio);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> latest(String projectId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return ascendente(
                em.createQuery(
                                "select m from ChatMessageEntity m where m.projectId = :p"
                                        + " order by m.seq desc",
                                ChatMessageEntity.class)
                        .setParameter("p", UUID.fromString(projectId))
                        .setMaxResults(limit)
                        .getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> before(String projectId, long seq, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return ascendente(
                em.createQuery(
                                "select m from ChatMessageEntity m"
                                        + " where m.projectId = :p and m.seq < :s"
                                        + " order by m.seq desc",
                                ChatMessageEntity.class)
                        .setParameter("p", UUID.fromString(projectId))
                        .setParameter("s", seq)
                        .setMaxResults(limit)
                        .getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> after(String projectId, long seq, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return em.createQuery(
                        "select m from ChatMessageEntity m"
                                + " where m.projectId = :p and m.seq > :s"
                                + " order by m.seq asc",
                        ChatMessageEntity.class)
                .setParameter("p", UUID.fromString(projectId))
                .setParameter("s", seq)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(JpaChatStore::aDominio)
                .toList();
    }

    // ------------------------------------------------------ historial filtrado (CU23)

    /** Carácter de escape del LIKE: ni barra invertida ni comillas, para no depender de cómo se escriben. */
    static final char ESCAPE = '!';

    /**
     * El WHERE se arma solo con fragmentos fijos y todos los valores van como parámetros: nada
     * del usuario se concatena al HQL.
     *
     * <p><b>Búsqueda:</b> {@code ilike} (Hibernate 6 lo traduce al ILIKE de PostgreSQL) sobre el
     * texto, acotado al proyecto. No distingue mayúsculas, pero <b>sí distingue acentos</b>
     * ("atributo" no encuentra "atribúto"); las mayúsculas fuera de ASCII dependen del LC_CTYPE de
     * la base. Con el comodín al principio no hay índice para el texto: se recorre el índice
     * {@code (project_id, seq)} del proyecto en el orden pedido y se cortan los primeros {@code
     * limit} que coinciden. Suficiente para el chat de un proyecto.
     *
     * <p>Los comodines del usuario ({@code %}, {@code _}) se escapan con {@value #ESCAPE}; la barra
     * invertida no es especial con {@code ESCAPE '!'}, así que también queda literal.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> find(ChatHistoryQuery consulta) {
        StringBuilder hql = new StringBuilder("select m from ChatMessageEntity m where m.projectId = :p");
        if (consulta.beforeSeq() != null) {
            hql.append(" and m.seq < :antes");
        }
        if (consulta.afterSeq() != null) {
            hql.append(" and m.seq > :despues");
        }
        if (consulta.authorType() != null) {
            hql.append(" and m.authorType = :tipo");
        }
        if (consulta.kind() != null) {
            hql.append(" and m.kind = :clase");
        }
        if (consulta.q() != null) {
            hql.append(" and m.text ilike :q escape '" + ESCAPE + "'");
        }
        boolean haciaAdelante = consulta.afterSeq() != null;
        hql.append(haciaAdelante ? " order by m.seq asc" : " order by m.seq desc");

        var query = em.createQuery(hql.toString(), ChatMessageEntity.class)
                .setParameter("p", consulta.projectId())
                .setMaxResults(consulta.limit());
        if (consulta.beforeSeq() != null) {
            query.setParameter("antes", consulta.beforeSeq());
        }
        if (consulta.afterSeq() != null) {
            query.setParameter("despues", consulta.afterSeq());
        }
        if (consulta.authorType() != null) {
            query.setParameter("tipo", consulta.authorType());
        }
        if (consulta.kind() != null) {
            query.setParameter("clase", consulta.kind());
        }
        if (consulta.q() != null) {
            query.setParameter("q", "%" + escaparLike(consulta.q()) + "%");
        }
        List<ChatMessageEntity> filas = query.getResultList();
        return haciaAdelante ? filas.stream().map(JpaChatStore::aDominio).toList() : ascendente(filas);
    }

    /** {@code !}, {@code %} y {@code _} pasan a ser literales; el resto queda igual. */
    static String escaparLike(String texto) {
        StringBuilder s = new StringBuilder(texto.length() + 8);
        for (char c : texto.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                s.append(ESCAPE);
            }
            s.append(c);
        }
        return s.toString();
    }

    // ------------------------------------------------------ estado de lectura (CU23)

    @Override
    @Transactional(readOnly = true)
    public ChatReadState readState(UUID projectId, UUID userId) {
        return jdbc.queryForObject(
                ESTADO_DE_LECTURA,
                (rs, i) -> new ChatReadState(
                        rs.getLong("last_read_seq"), rs.getLong("unread"), rs.getLong("last_seq")),
                projectId, projectId, userId, projectId, userId);
    }

    @Override
    @Transactional
    public ChatReadState markRead(UUID projectId, UUID userId, long upToSeq) {
        jdbc.update(MARCAR_LEIDO, projectId, userId, upToSeq, projectId);
        return readState(projectId, userId);
    }

    /** "Los N más cercanos hacia atrás" se leen descendentes por el índice y se dan vuelta. */
    private static List<ChatMessage> ascendente(List<ChatMessageEntity> descendente) {
        List<ChatMessage> mensajes =
                new ArrayList<>(descendente.stream().map(JpaChatStore::aDominio).toList());
        Collections.reverse(mensajes);
        return mensajes;
    }

    // --------------------------------------------------------------- mapeo

    static ChatMessageEntity aFila(ChatMessage m) {
        // Microsegundos, que es lo que guarda TIMESTAMPTZ: así el mensaje que se difunde en vivo
        // y el que se lee después del historial tienen exactamente la misma hora.
        Instant creado =
                (m.createdAt() == null ? Instant.now() : m.createdAt())
                        .truncatedTo(ChronoUnit.MICROS);
        return new ChatMessageEntity(
                UUID.fromString(m.id()),
                UUID.fromString(m.projectId()),
                uuidONulo(m.diagramId()),
                m.seq(),
                m.authorType(),
                uuidONulo(m.authorId()),
                m.authorName(),
                m.authorColor(),
                uuidONulo(m.addressedToId()),
                m.addressedToName(),
                uuidONulo(m.replyToId()),
                m.clientMessageId(),
                m.kind(),
                m.text(),
                metaAJson(m.meta()),
                // OffsetDateTime en la entidad, como el resto de las columnas TIMESTAMPTZ del
                // proyecto; el dominio sigue hablando en Instant.
                creado.atOffset(ZoneOffset.UTC));
    }

    static ChatMessage aDominio(ChatMessageEntity f) {
        return new ChatMessage(
                f.getId().toString(),
                f.getProjectId().toString(),
                texto(f.getDiagramId()),
                f.getSeq(),
                f.getAuthorType(),
                texto(f.getAuthorId()),
                f.getAuthorName(),
                f.getAuthorColor(),
                texto(f.getAddressedToId()),
                f.getAddressedToName(),
                texto(f.getReplyToId()),
                f.getClientMessageId(),
                f.getKind(),
                f.getText(),
                f.getCreatedAt() == null ? null : f.getCreatedAt().toInstant(),
                metaDeJson(f.getMeta()));
    }

    private static String metaAJson(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        try {
            return JSON_META.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("meta del mensaje no se puede serializar", e);
        }
    }

    private static Map<String, Object> metaDeJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON_META.readValue(json, TIPO_META);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("meta ilegible en chat_messages", e);
        }
    }

    private static UUID uuidONulo(String valor) {
        if (valor == null) {
            return null;
        }
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String texto(UUID valor) {
        return valor == null ? null : valor.toString();
    }

    /**
     * ¿La falla es el índice único de {@code client_message_id}? Se mira el nombre del índice y
     * no sólo el SQLSTATE 23505, porque una colisión de {@code (project_id, seq)} también es
     * 23505 y esa sí es un error real que no se debe disfrazar de reintento.
     */
    static boolean esDuplicadoDeCliente(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase().contains(INDICE_CLIENT_MESSAGE)) {
                return true;
            }
            if (t instanceof SQLException sql
                    && SQLSTATE_UNIQUE.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(INDICE_CLIENT_MESSAGE)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** ¿Hay una violación de unicidad (SQLSTATE 23505) en la cadena de causas? */
    static boolean esViolacionDeUnicidad(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.JDBCException jdbc
                    && SQLSTATE_UNIQUE.equals(jdbc.getSQLState())) {
                return true;
            }
            if (t instanceof SQLException sql && SQLSTATE_UNIQUE.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
