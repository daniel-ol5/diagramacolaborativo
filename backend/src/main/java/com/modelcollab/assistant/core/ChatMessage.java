package com.modelcollab.assistant.core;

import java.time.Instant;
import java.util.Map;

/**
 * Un mensaje del chat grupal de un proyecto (CU19), tal como lo maneja el dominio y tal como
 * viaja a los clientes.
 *
 * <p>Está libre de anotaciones de persistencia a propósito: lo comparten el chat, el orquestador
 * de la IA y los casos de uso vecinos, y no tiene por qué saber dónde se guarda. El mapeo a la
 * tabla {@code chat_messages} (V4) vive en {@code ChatMessageEntity}.
 *
 * <p>Los ids viajan como texto: es lo que leen la web y el móvil, y así el contrato de cable no
 * depende del tipo de columna.
 *
 * @param seq orden total del chat dentro del proyecto, creciente. Es lo que el cliente usa para
 *     ordenar y para pedir "lo anterior a" / "lo posterior a"; {@code createdAt} es sólo
 *     informativo, porque dos relojes no dan un orden confiable.
 * @param authorColor el mismo color de los cursores y chips de presencia para ese usuario
 *     ({@code Participante.de}). Se guarda y no se recalcula para que el historial conserve el
 *     color aunque la paleta cambie. {@code null} para la IA y el sistema.
 * @param addressedToId a quién va dirigido (la respuesta de la IA lleva a quien preguntó).
 * @param clientMessageId id que genera el cliente para la idempotencia, único por proyecto.
 * @param meta datos extra de cada caso de uso; valores simples, nunca objetos del dominio.
 */
public record ChatMessage(
        String id,
        String projectId,
        String diagramId,
        long seq,
        ChatAuthorType authorType,
        String authorId,
        String authorName,
        String authorColor,
        String addressedToId,
        String addressedToName,
        String replyToId,
        String clientMessageId,
        ChatMessageKind kind,
        String text,
        Instant createdAt,
        Map<String, Object> meta) {}
