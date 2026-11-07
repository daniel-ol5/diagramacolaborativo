package com.modelcollab.assistant.store;

import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public class InMemoryChatStore implements ChatStore {

    /** Mensajes de cada proyecto, en orden de seq. Cada lista se protege con su propio monitor. */
    private final Map<String, List<ChatMessage>> porProyecto = new ConcurrentHashMap<>();

    private final Map<String, AtomicLong> secuencias = new ConcurrentHashMap<>();

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
