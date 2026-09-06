import type { Node } from "reactflow";
import type { CanonicalModel, ClassEntity } from "../types/canonical";

/** Convierte las clases del modelo canonico en nodos de React Flow (Dia 6: sin aristas todavia). */
export function toFlowNodes(model: CanonicalModel): Node<ClassEntity>[] {
  return model.classes.map((classEntity) => ({
    id: classEntity.id,
    type: "umlClass",
    position: classEntity.position,
    style: { width: classEntity.width, height: classEntity.height },
    data: classEntity,
  }));
}
