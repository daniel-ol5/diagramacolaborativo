package com.modelcollab.ai.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Una operación tal como la propone el modelo de lenguaje. <b>Todavía no es una mutación.</b>
 *
 * <h2>Por qué {@code target} es un NOMBRE y no un id</h2>
 *
 * <p>La tentación es pasarle al modelo los ids del diagrama y pedirle que los devuelva. Es mala
 * idea por dos motivos. Un modelo de lenguaje copia mal un UUID —lo parafrasea, le cambia un
 * dígito— y entonces la operación apunta a un elemento que no existe. Y sobre todo: <b>la
 * identidad de los elementos es del servidor</b>. Si el modelo pudiera elegir ids, un id
 * inventado que por casualidad coincida con otro elemento lo pisa.
 *
 * <p>Así que el contrato con la IA habla de nombres, que es de lo que habla el usuario, y el
 * servidor resuelve nombre → id contra el diagrama real. Un nombre que no existe se convierte en
 * una frase que se entiende —"no encontré ninguna clase llamada Proveedor"— en vez de en una
 * referencia colgada dentro del modelo canónico.
 *
 * <p>{@code args} son los datos propios de cada operación. El traductor comprueba que no venga
 * ninguna clave que esa operación no admita: un campo de más significa que el modelo se desvió
 * del contrato, y eso se rechaza igual que se rechaza de cualquier cliente.
 */
public record AiOperation(String op, String target, Map<String, Object> args) {

    public AiOperation {
        // LinkedHashMap y no Map.copyOf: copyOf revienta si el modelo mandó un valor null, y un
        // null es un dato a rechazar con un mensaje, no una excepción a mitad de la copia.
        args = args == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    /** El valor de {@code args} como texto, o {@code null} si no vino. */
    public String texto(String clave) {
        Object valor = args.get(clave);
        return valor == null ? null : String.valueOf(valor);
    }
}
