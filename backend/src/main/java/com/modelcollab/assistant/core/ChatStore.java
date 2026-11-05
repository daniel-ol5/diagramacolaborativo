package com.modelcollab.assistant.core;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de almacenamiento del chat grupal. Implementaciones: {@code JpaChatStore} (producción,
 * PostgreSQL) e {@code InMemoryChatStore} (tests y desarrollo sin base de datos).
 *
 * <p>Las tres consultas de historial devuelven <b>siempre en orden ascendente por seq</b>, que es
 * el orden en que se leen; quien pide "los últimos 50" quiere verlos de arriba hacia abajo.
 */
public interface ChatStore {

    /**
     * Guarda un mensaje nuevo.
     *
     * @throws org.springframework.dao.DuplicateKeyException si ya existe un mensaje con el mismo
     *     {@code (projectId, clientMessageId)}.
     */
    ChatMessage save(ChatMessage message);

    Optional<ChatMessage> findByClientMessageId(String projectId, String clientMessageId);

    /**
     * Un mensaje por id, acotado al proyecto: un id de otro proyecto no se encuentra. Lo usa la
     * validación de {@code replyToId} y el orquestador para saber si se le responde a la IA.
     */
    Optional<ChatMessage> findById(String projectId, String id);

    /**
     * Siguiente número de secuencia del proyecto, empezando en 1. <b>Atómico</b>: dos llamadas
     * concurrentes nunca reciben el mismo número.
     */
    long nextSeq(String projectId);

    /** Los últimos {@code limit} mensajes del proyecto. */
    List<ChatMessage> latest(String projectId, int limit);

    /** Hasta {@code limit} mensajes con seq &lt; {@code seq}, los más cercanos a él. */
    List<ChatMessage> before(String projectId, long seq, int limit);

    /** Hasta {@code limit} mensajes con seq &gt; {@code seq}, los más cercanos a él. */
    List<ChatMessage> after(String projectId, long seq, int limit);
}
