package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessageKind;
import java.util.Objects;
import java.util.UUID;

/**
 * Una consulta del historial con filtros (CU23), ya validada por {@link ChatService}.
 *
 * <ul>
 *   <li>Sin cursores: los {@code limit} más nuevos del conjunto filtrado.
 *   <li>{@code beforeSeq}: los {@code limit} más cercanos con seq menor.
 *   <li>{@code afterSeq}: los {@code limit} más cercanos con seq mayor.
 * </ul>
 *
 * <p>El resultado va siempre ascendente por seq. {@code limit} es lo que se le pide al store: el
 * servicio pasa uno de más para calcular {@code hasMore}.
 *
 * @param q texto literal ya recortado (sin comodines: el store escapa lo que haga falta), o null.
 * @param authorType null = cualquiera.
 * @param kind null = cualquiera.
 */
public record ChatHistoryQuery(
        UUID projectId,
        Long beforeSeq,
        Long afterSeq,
        int limit,
        String q,
        ChatAuthorType authorType,
        ChatMessageKind kind) {

    public ChatHistoryQuery {
        Objects.requireNonNull(projectId, "projectId");
        if (beforeSeq != null && afterSeq != null) {
            throw new IllegalArgumentException("beforeSeq y afterSeq no van juntos");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit debe ser positivo");
        }
    }
}
