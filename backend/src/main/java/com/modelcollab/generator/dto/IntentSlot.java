package com.modelcollab.generator.dto;

/**
 * Una ranura de datos: el pedazo de la frase dictada que hay que extraer (§10.3).
 *
 * <p>{@code type} es el tipo del metamodelo y le dice a la app <b>qué analizador local</b> usar:
 * {@code DATE} resuelve "hoy" y "el lunes", {@code DATETIME} resuelve "10 am", {@code DECIMAL} e
 * {@code INTEGER} extraen cantidades, {@code VARCHAR} captura nombres propios.
 *
 * <p>{@code references} es el nombre de otra entidad cuando la ranura no es un dato suelto sino
 * una <b>referencia</b>. Sale de las relaciones del diagrama, no de los atributos: en el ejemplo
 * de §10.3, la ranura {@code cliente} de "agenda una cita con Carlos" no existe como atributo de
 * Cita — existe porque hay una asociación con Cliente. Sin este campo, la app recibiría el texto
 * "Carlos" y no sabría que tiene que buscar un Cliente con ese nombre y mandar su id.
 */
public record IntentSlot(String name, String type, boolean required, String references) {

    public static IntentSlot deAtributo(String nombre, String tipo, boolean obligatoria) {
        return new IntentSlot(nombre, tipo, obligatoria, null);
    }

    public static IntentSlot deRelacion(String nombre, String entidad, boolean obligatoria) {
        // VARCHAR porque lo que se dicta es un nombre ("con Carlos"), no un identificador:
        // nadie dicta un UUID. La app lo resuelve contra la entidad que dice references.
        return new IntentSlot(nombre, "VARCHAR", obligatoria, entidad);
    }

    /** La misma ranura, pero opcional: buscar y eliminar aceptan cualquier criterio. */
    public IntentSlot opcional() {
        return new IntentSlot(name, type, false, references);
    }
}
