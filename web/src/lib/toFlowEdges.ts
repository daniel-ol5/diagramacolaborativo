import type { Edge } from "reactflow";
import type { CanonicalModel, Relationship } from "../types/canonical";

/** Convierte las relaciones del modelo canonico en aristas de React Flow. */
export function toFlowEdges(model: CanonicalModel): Edge<Relationship>[] {
  return model.relationships.map((relationship) => ({
    id: relationship.id,
    source: relationship.sourceClassId,
    target: relationship.targetClassId,
    type: "umlRelationship",
    data: relationship,
  }));
}
