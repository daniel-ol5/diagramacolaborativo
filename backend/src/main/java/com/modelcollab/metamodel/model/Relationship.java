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
 * {@code @OneToMany(mappedBy = ...)}. Pero cuánto le cree el generador depende del tipo:
 *
 * <ul>
 *   <li><b>1..N (ASSOCIATION, AGGREGATION, COMPOSITION):</b> el lado dueño está
 *       determinado mecánicamente por las multiplicidades — es siempre el lado "muchos".
 *       El generador lo <b>deriva</b> de ellas; aquí {@code owningSide} es sólo
 *       informativo. Un diagrama escrito a mano, o generado por Gemini en la Fase 4,
 *       puede traerlo invertido, y confiar en él pondría las FK del lado equivocado.
 *       Cuando contradice a las multiplicidades, el MetamodelValidator emite advertencia.</li>
 *   <li><b>MANY_TO_MANY:</b> acá {@code owningSide} <b>sí manda</b>. Es el único caso donde
 *       el campo es autoritativo, porque es una elección genuina del modelador: decide de
 *       qué lado va {@code @JoinTable} y de cuál el {@code mappedBy}.</li>
 *   <li><b>INHERITANCE / DEPENDENCY:</b> no aplica; tampoco llevan multiplicidades.</li>
 * </ul>
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
