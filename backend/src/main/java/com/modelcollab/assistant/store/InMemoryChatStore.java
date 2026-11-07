package com.modelcollab.assistant.store;

import com.modelcollab.assistant.chat.ChatHistoryQuery;
import com.modelcollab.assistant.chat.ChatHistoryStore;
import com.modelcollab.assistant.chat.ChatReadState;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * {@link ChatStore} en memoria. Es el que usan los tests —{@code ./mvnw clean test} corre sin
 * base de datos— y el que se activa con {@code CHAT_STORE=memory}. No persiste nada: reiniciar
 * el backend vacía el chat.
 *
 * <p>Imita las garantías de {@code JpaChatStore} que importan: la unicidad de {@code (projectId,
 * clientMessageId)} con la misma excepción y una secuencia atómica por proyecto.
 */
@Component
@ConditionalOnProperty(name = "modelcollab.chat.store", havingValue = "memory")
public class InMemoryChatStore implements ChatStore, ChatHistoryStore {

    /** Mensajes de cada proyecto, en orden de seq. Cada lista se protege con su propio monitor. */
    private final Map<String, List<ChatMessage>> porProyecto = new ConcurrentHashMap<>();

    private final Map<String, AtomicLong> secuencias = new ConcurrentHashMap<>();

    /** CU23: (proyecto, usuario) → último seq leído. {@code merge} con max: solo avanza. */
    private final Map<List<UUID>, Long> leidos = new ConcurrentHashMap<>();

    @Override
    public ChatMessage save(ChatMessage message) {
        List<ChatMessage> mensajes =
                porProyecto.computeIfAbsent(message.projectId(), p -> new ArrayList<>());
        synchronized (mensajes) {
            if (message.clientMessageId() != null
                    && mensajes.stream()
                            .anyMatch(m -> message.clientMessageId().equals(m.clientMessageId()))) {
                throw new DuplicateKeyException(
                        "Mensaje duplicado: " + message.projectId() + "/"
                                + message.clientMessageId());
            }
            // Inserción ordenada por seq: con dos escritores concurrentes el orden de llegada a
            // save no tiene por qué ser el orden de seq, y el historial se lee por seq.
            int i = mensajes.size();
            while (i > 0 && mensajes.get(i - 1).seq() > message.seq()) {
                i--;
            }
            mensajes.add(i, message);
        }
        return message;
    }

    @Override
    public Optional<ChatMessage> findByClientMessageId(String projectId, String clientMessageId) {
        return buscar(projectId, m -> clientMessageId.equals(m.clientMessageId()));
    }

    @Override
    public Optional<ChatMessage> findById(String projectId, String id) {
        return buscar(projectId, m -> id.equals(m.id()));
    }

    @Override
    public long nextSeq(String projectId) {
        return secuencias.computeIfAbsent(projectId, p -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public List<ChatMessage> latest(String projectId, int limit) {
        List<ChatMessage> todos = copia(projectId);
        return todos.subList(Math.max(0, todos.size() - limit), todos.size());
    }

    @Override
    public List<ChatMessage> before(String projectId, long seq, int limit) {
        List<ChatMessage> anteriores =
                copia(projectId).stream().filter(m -> m.seq() < seq).toList();
        return anteriores.subList(Math.max(0, anteriores.size() - limit), anteriores.size());
    }

    @Override
    public List<ChatMessage> after(String projectId, long seq, int limit) {
        return copia(projectId).stream().filter(m -> m.seq() > seq).limit(limit).toList();
    }

    // ------------------------------------------------------ historial filtrado (CU23)

    /**
     * Mismo contrato que en PostgreSQL. {@code q} es literal ({@code contains}, sin comodines) y
     * sin distinguir mayúsculas con {@code Locale.ROOT}; como ILIKE, distingue acentos.
     */
    @Override
    public List<ChatMessage> find(ChatHistoryQuery consulta) {
        String q = consulta.q() == null ? null : consulta.q().toLowerCase(Locale.ROOT);
        List<ChatMessage> filtrados = copia(consulta.projectId().toString()).stream()
                .filter(m -> consulta.beforeSeq() == null || m.seq() < consulta.beforeSeq())
                .filter(m -> consulta.afterSeq() == null || m.seq() > consulta.afterSeq())
                .filter(m -> consulta.authorType() == null || consulta.authorType() == m.authorType())
                .filter(m -> consulta.kind() == null || consulta.kind() == m.kind())
                .filter(m -> q == null || (m.text() != null && m.text().toLowerCase(Locale.ROOT).contains(q)))
                .toList();
        if (consulta.afterSeq() != null) {
            return filtrados.stream().limit(consulta.limit()).toList();
        }
        return filtrados.subList(Math.max(0, filtrados.size() - consulta.limit()), filtrados.size());
    }

    // ------------------------------------------------------ estado de lectura (CU23)

    @Override
    public ChatReadState readState(UUID projectId, UUID userId) {
        long leido = leidos.getOrDefault(List.of(projectId, userId), 0L);
        List<ChatMessage> todos = copia(projectId.toString());
        long ultimo = todos.isEmpty() ? 0 : todos.get(todos.size() - 1).seq();
        String yo = userId.toString();
        long sinLeer = todos.stream()
                .filter(m -> m.seq() > leido)
                .filter(m -> !yo.equals(m.authorId()))
                .count();
        return new ChatReadState(leido, sinLeer, ultimo);
    }

    @Override
    public ChatReadState markRead(UUID projectId, UUID userId, long upToSeq) {
        List<ChatMessage> todos = copia(projectId.toString());
        long ultimo = todos.isEmpty() ? 0 : todos.get(todos.size() - 1).seq();
        leidos.merge(List.of(projectId, userId), Math.min(upToSeq, ultimo), Math::max);
        return readState(projectId, userId);
    }

    private Optional<ChatMessage> buscar(
            String projectId, java.util.function.Predicate<ChatMessage> criterio) {
        return copia(projectId).stream().filter(criterio).findFirst();
    }

    private List<ChatMessage> copia(String projectId) {
        List<ChatMessage> mensajes = porProyecto.get(projectId);
        if (mensajes == null) {
            return List.of();
        }
        synchronized (mensajes) {
            return new ArrayList<>(mensajes);
        }
    }
}
