package com.modelcollab.metamodel.model;

import java.util.List;

/**
 * Relación entre dos clases. Defaults de §7: owningSide SOURCE, isNavigable true.
 * joinTableName sólo aplica a MANY_TO_MANY; sourceRole/targetRole son opcionales y
 * quedan en null si el diagrama no los define.
 *
 * <h2>Autoridad de {@code owningSide} (regla fijada el 3/9, no la cambies sin acordarlo)</h2>
 *
 * <p>{@code owningSide} significa <em>el lado donde vive la FK</em>, que coincide con la
 * semántica JPA: el lado dueño es el que lleva {@code @ManyToOne} y el otro lleva
 * {@code @OneToMany(mappedBy = ...)}. Pero el generador <b>no le cree ciegamente</b>: casi
 * siempre el lado dueño es derivable de las multiplicidades, y un diagrama escrito a mano —o
 * generado por Gemini en la Fase 4— puede traer el campo invertido y poner las FK del lado
 * equivocado en silencio. La regla, por caso:
 *
 * <ol>
 *   <li><b>Hay un extremo de colección ({@code 0..*} o {@code 1..*}):</b> <b>derivado</b>.
 *       La FK va en el lado "muchos". {@code owningSide} es informativo; si contradice a las
 *       multiplicidades, el MetamodelValidator emite advertencia.</li>
 *   <li><b>Ambos extremos escalares y DISTINTOS ({@code 1..1} ↔ {@code 0..1}):</b>
 *       <b>derivado</b> también. La FK va en el extremo "flojo", el {@code 0..1}. No es una
 *       convención arbitraria sino diseño de base de datos: puesta en el extremo obligatorio,
 *       la columna tendría que ser nullable sobre la mayoría de las filas más un UNIQUE para
 *       simular el 1:1; puesta en el extremo flojo, es NOT NULL y no deja huérfanos.
 *       {@code owningSide} informativo; advertencia si contradice.</li>
 *   <li><b>Ambos extremos escalares e IGUALES ({@code 1..1} ↔ {@code 1..1}, o
 *       {@code 0..1} ↔ {@code 0..1}):</b> acá no hay respuesta mecánica, así que
 *       {@code owningSide} es <b>AUTORITATIVO</b>. Es el caso peligroso: el campo decide el
 *       esquema de base de datos sin que nadie pueda verificarlo, y como el constructor
 *       compacto ya normalizó el default, no se distingue "vino ausente" de "lo eligieron".
 *       Por eso el MetamodelValidator debe emitir <b>siempre</b> una advertencia acá, del
 *       tipo "owningSide no derivable en esta relación simétrica; se usa el valor declarado,
 *       verificá que sea intencional".</li>
 *   <li><b>MANY_TO_MANY:</b> <b>AUTORITATIVO</b>. Es una elección genuina del modelador:
 *       decide de qué lado va {@code @JoinTable} y de cuál el {@code mappedBy}.</li>
 *   <li><b>INHERITANCE / DEPENDENCY:</b> no aplica; tampoco llevan multiplicidades.</li>
 * </ol>
 *
 * <p>Si {@code joinTableName} viene ausente en un MANY_TO_MANY, el generador deriva el
 * nombre como {@code {source}_{target}} en snake_case.
 */
public record Relationship(
        String id,
        String sourceClassId,
        String targetClassId,
        RelationshipType type,
        OwningSide owningSide,
        String joinTableName,
        Multiplicity sourceMultiplicity,
        Multiplicity targetMultiplicity,
        String sourceRole,
        String targetRole,
        Boolean isNavigable,
        List<Waypoint> waypoints) {

    public Relationship {
        owningSide = owningSide == null ? OwningSide.SOURCE : owningSide;
        isNavigable = isNavigable == null || isNavigable;
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }
}
