import type { Multiplicity, OwningSide, RelationshipType } from "../types/canonical";

const ES_COLECCION = (m: Multiplicity) => m === "0..*" || m === "1..*";

/**
 * Deriva `owningSide` de las multiplicidades, regla fijada el Dia 1 (D-08,
 * ver Relationship.java): el lado dueño es el que lleva la FK. `null`
 * significa "no derivable" — MANY_TO_MANY y los casos simetricos (1..1↔1..1,
 * 0..1↔0..1) quedan a eleccion del usuario, con SOURCE de default.
 *
 * 1. Un extremo es coleccion (0..* o 1..*): la FK va del lado "muchos".
 * 2. Los dos escalares y DISTINTOS (1..1 vs 0..1): la FK va en el "flojo" (0..1).
 * 3. Los dos escalares e IGUALES, o MANY_TO_MANY: no derivable.
 * 4. INHERITANCE/DEPENDENCY: no aplica (no llevan multiplicidad).
 */
export function deriveOwningSide(
  type: RelationshipType,
  sourceMultiplicity: Multiplicity,
  targetMultiplicity: Multiplicity,
): OwningSide | null {
  if (type === "MANY_TO_MANY" || type === "INHERITANCE" || type === "DEPENDENCY") return null;

  const sourceEsColeccion = ES_COLECCION(sourceMultiplicity);
  const targetEsColeccion = ES_COLECCION(targetMultiplicity);
  if (sourceEsColeccion !== targetEsColeccion) {
    return sourceEsColeccion ? "SOURCE" : "TARGET";
  }

  if (sourceMultiplicity !== targetMultiplicity) {
    if (sourceMultiplicity === "0..1") return "SOURCE";
    if (targetMultiplicity === "0..1") return "TARGET";
  }

  return null;
}
