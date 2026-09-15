package com.modelcollab.generator.dto;

import java.util.List;

/**
 * Una intención del catálogo NLU (§10.3, RF-08.3).
 *
 * <p>{@code triggers} son las frases que la disparan — el "diccionario de sinónimos" que pide
 * RF-08.3. Son varias a propósito: el reconocimiento de voz devuelve lo que entendió, no lo que
 * se dijo, y con una sola frase exacta la app no responde nunca.
 */
public record Intent(
        String name,
        String entity,
        String action,
        List<String> triggers,
        List<IntentSlot> slots) {

    public Intent {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
