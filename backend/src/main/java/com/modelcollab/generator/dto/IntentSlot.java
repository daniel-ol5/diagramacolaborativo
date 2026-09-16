package com.modelcollab.generator.dto;

/**
 * Una ranura de datos: el pedazo de la frase dictada que hay que extraer (§10.3).
 *
 * <p>{@code type} es el tipo del metamodelo y le dice a la app <b>qué analizador local</b> usar:
 * {@code DATE} resuelve "hoy" y "el lunes", {@code DATETIME} resuelve "10 am", {@code DECIMAL} e
 * {@code INTEGER} extraen cantidades, {@code VARCHAR} captura nombres propios.
 *
 * <h2>{@code name} es lo que se dice; {@code field} es lo que viaja</h2>
 *
 * <p>Para un atributo son iguales. Para una referencia NO: el usuario dice <i>"con Carlos"</i> y
 * la ranura se llama {@code cliente}, pero el DTO del backend generado espera el campo
 * {@code clienteId} con un identificador adentro. Mandar el nombre hablado como clave del JSON
 * hace que el servidor <b>descarte el dato y rechace la petición</b> por el campo obligatorio
 * que falta — y el usuario no tiene forma de hacer nada al respecto, porque dictó la orden
 * completa.
 *
 * <p>{@code field} se calcula con la misma función que usa el generador del backend para nombrar
 * ese campo, así que respeta el rol de la relación: si la asociación declara {@code targetRole}
 * "titular", el campo es {@code titularId} y no {@code clienteId}.
 *
 * <p>{@code references} es el nombre de otra entidad cuando la ranura no es un dato suelto sino
 * una <b>referencia</b>. Sale de las relaciones del diagrama, no de los atributos: en el ejemplo
 * de §10.3, la ranura {@code cliente} de "agenda una cita con Carlos" no existe como atributo de
 * Cita — existe porque hay una asociación con Cliente. Sin este campo, la app recibiría el texto
 * "Carlos" y no sabría que tiene que buscar un Cliente con ese nombre y mandar su id.
 */
public record IntentSlot(
        String name, String field, String type, boolean required, String references) {

    public static IntentSlot deAtributo(String nombre, String tipo, boolean obligatoria) {
        // En un atributo el nombre hablado y el campo del cable son el mismo.
        return new IntentSlot(nombre, nombre, tipo, obligatoria, null);
    }

    public static IntentSlot deRelacion(
            String nombre, String campo, String entidad, boolean obligatoria) {
        // VARCHAR porque lo que se dicta es un nombre ("con Carlos"), no un identificador:
        // nadie dicta un UUID. La app lo resuelve contra la entidad que dice references y manda
        // el id resultante bajo la clave que dice field.
        return new IntentSlot(nombre, campo, "VARCHAR", obligatoria, entidad);
    }

    /** La misma ranura, pero opcional: buscar y eliminar aceptan cualquier criterio. */
    public IntentSlot opcional() {
        return new IntentSlot(name, field, type, false, references);
    }
}
