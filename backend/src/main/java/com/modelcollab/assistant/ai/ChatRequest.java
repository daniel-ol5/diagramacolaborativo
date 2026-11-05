package com.modelcollab.assistant.ai;

import java.util.List;

/**
 * Pedido de varios turnos al modelo del chat (CU19). No fuerza JSON: la respuesta es texto
 * libre para mostrar en el chat.
 *
 * @param system instrucciones del sistema.
 * @param turns la conversación, de la más vieja a la más nueva; el último es la pregunta actual.
 * @param temperature {@value #TEMPERATURA_POR_DEFECTO} si viene nula.
 * @param maxTokens {@value #MAX_TOKENS_POR_DEFECTO} si viene nulo.
 * @param askerFirstName nombre de pila de quien preguntó ahora. No se le manda al proveedor como
 *     campo propio (ya va en el system prompt y en los turnos); existe para el cliente simulado y
 *     para quien necesite saber a quién se le está respondiendo.
 */
public record ChatRequest(
        String system,
        List<ChatTurn> turns,
        Double temperature,
        Integer maxTokens,
        String askerFirstName) {

    public static final double TEMPERATURA_POR_DEFECTO = 0.4;
    public static final int MAX_TOKENS_POR_DEFECTO = 400;

    public ChatRequest {
        system = system == null ? "" : system;
        turns = turns == null ? List.of() : List.copyOf(turns);
        temperature = temperature == null ? TEMPERATURA_POR_DEFECTO : temperature;
        maxTokens = maxTokens == null ? MAX_TOKENS_POR_DEFECTO : maxTokens;
    }

    /** Con temperatura y tokens por defecto. */
    public static ChatRequest of(String system, List<ChatTurn> turns, String askerFirstName) {
        return new ChatRequest(system, turns, null, null, askerFirstName);
    }
}
