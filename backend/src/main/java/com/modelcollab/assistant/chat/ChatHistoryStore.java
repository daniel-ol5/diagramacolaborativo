package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatMessage;
import java.util.List;
import java.util.UUID;

/**
 * Consultas del historial del chat (CU23) que no están en el puerto {@code ChatStore} de
 * {@code assistant/core}, que es compartido y no se toca. Lo implementan {@code JpaChatStore} e
 * {@code InMemoryChatStore}. Solo lectura del chat: nada aquí edita ni borra mensajes.
 */
public interface ChatHistoryStore {

    /**
     * Mensajes del proyecto que cumplen todos los filtros de la consulta, como mucho {@code
     * limit}, ascendentes por seq. {@code q} se busca como texto literal, sin distinguir
     * mayúsculas, solo dentro del proyecto.
     */
    List<ChatMessage> find(ChatHistoryQuery query);

    /** Estado de lectura de {@code userId} en el proyecto; usuario sin fila = lastReadSeq 0. */
    ChatReadState readState(UUID projectId, UUID userId);

    /**
     * Marca como leído hasta {@code upToSeq}, recortado al último seq del proyecto, y solo si
     * avanza: {@code lastReadSeq = max(actual, min(upToSeq, lastSeq))}. Atómico e idempotente.
     *
     * @param upToSeq no negativo (lo valida el servicio).
     * @return el estado después de marcar.
     */
    ChatReadState markRead(UUID projectId, UUID userId, long upToSeq);
}
