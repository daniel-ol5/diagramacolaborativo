package com.modelcollab.generator.dto;

/**
 * Una columna del esquema físico.
 *
 * <p>Las columnas de clave foránea <b>no existen en el diagrama</b>: las sintetiza el generador a
 * partir de una relación, del lado que manda {@code owningSide}. Son justamente las que el
 * navegador no podía adivinar, y las que hacen que la vista ER se parezca a la base de datos y no
 * al diagrama de clases.
 *
 * @param name nombre físico <b>sin comillas</b>
 * @param quoted si es palabra reservada de PostgreSQL y el DDL la entrecomilla
 * @param type el tipo tal como lo dibujó el modelador: {@code VARCHAR}, {@code BIGINT},
 *     {@code DECIMAL}… En una clave foránea, el tipo de la clave primaria a la que apunta.
 *     <b>No es el tipo exacto que declara PostgreSQL</b> —eso lo decide Hibernate al crear la
 *     tabla— sino lo que el diagrama dice, que es lo que corresponde mostrar en un ER
 * @param length largo de un {@code VARCHAR}, o {@code null}
 * @param precision precisión de un {@code DECIMAL}, o {@code null}
 * @param scale escala de un {@code DECIMAL}, o {@code null}
 * @param primaryKey si es clave primaria
 * @param generated si la genera la base y no el cliente. En una clave inyectada por el generador
 *     —§11.2.2, la clase no declaraba ninguna— esto es lo único que lo delata
 * @param nullable si admite nulos
 * @param unique si lleva restricción de unicidad
 * @param references nombre de la tabla a la que apunta si es clave foránea, o {@code null}. Es lo
 *     que el cliente usa para dibujar la línea entre dos tablas
 */
public record SchemaColumn(
        String name,
        boolean quoted,
        String type,
        Integer length,
        Integer precision,
        Integer scale,
        boolean primaryKey,
        boolean generated,
        boolean nullable,
        boolean unique,
        String references) {}
