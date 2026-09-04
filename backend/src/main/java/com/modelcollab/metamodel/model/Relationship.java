package com.modelcollab.metamodel.model;

import java.util.List;

/**
 * Relación entre dos clases. Defaults de §7: owningSide SOURCE, isNavigable true.
 * joinTableName sólo aplica a MANY_TO_MANY; sourceRole/targetRole son opcionales y
 * quedan en null si el diagrama no los define.
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
