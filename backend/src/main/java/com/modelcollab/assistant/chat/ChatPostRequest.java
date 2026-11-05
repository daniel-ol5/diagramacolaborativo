package com.modelcollab.assistant.chat;

import java.util.UUID;

/**
 * Lo que manda el cliente para escribir en el chat, por STOMP o por REST. <b>No trae autor</b>:
 * quién escribe sale siempre del Principal del JWT, y como el ObjectMapper canónico rechaza
 * campos desconocidos, un {@code authorId} en el cuerpo ni siquiera se deserializa.
 *
 * @param clientMessageId id generado por el cliente; reintentar con el mismo no duplica.
 * @param diagramId opcional: el diagrama que la persona tiene abierto, como contexto.
 * @param replyToId opcional: el mensaje al que responde. Si es de la IA, en modo ON_MENTION la IA
 *     contesta aunque no la mencionen.
 */
public record ChatPostRequest(
        String clientMessageId, String text, UUID diagramId, String replyToId) {}
