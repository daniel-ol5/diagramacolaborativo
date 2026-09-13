package com.modelcollab.ai.dto;

import java.util.List;

/**
 * El JSON que el modelo de lenguaje tiene que devolver: esquema fijo y temperatura 0.
 *
 * <p>{@code intent} es lo que el modelo dice que le pidieron: {@code EDIT_MODEL} para una
 * edición puntual, {@code GENERATE_DOMAIN} para "creame un sistema para un banco",
 * {@code UNSUPPORTED} para lo que no sabe traducir.
 *
 * <h2>Que el modelo clasifique NO es el guardarraíl</h2>
 *
 * <p>Es una opinión suya sobre su propia salida, y pedirle a un modelo que se autocensure es
 * justamente la garantía que no se puede dar: el día que devuelva {@code EDIT_MODEL} con quince
 * clases adentro, nadie lo habría prometido de menos.
 *
 * <p>El guardarraíl de verdad son las reglas que el servidor aplica sobre lo que vuelve: el tope
 * duro de operaciones, la lista blanca de operaciones permitidas y la resolución de nombres
 * contra el diagrama real. Esta clasificación es una señal más, gratis, que se aprovecha cuando
 * llega — y que además funciona al revés de lo peligroso: si el modelo se autodenuncia, mejor.
 *
 * <p>{@code reply} es la frase para mostrarle al usuario.
 */
public record AiPlan(String intent, String reply, List<AiOperation> operations) {

    public static final String EDITAR = "EDIT_MODEL";
    public static final String GENERAR_DOMINIO = "GENERATE_DOMAIN";
    public static final String NO_SOPORTADO = "UNSUPPORTED";

    public AiPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
