import type { Node } from "reactflow";
import type { CanonicalModel, ClassEntity } from "../types/canonical";
import { normalizeClass } from "./normalize";

/** Convierte las clases del modelo canonico en nodos de React Flow. */
export function toFlowNodes(model: CanonicalModel): Node<ClassEntity>[] {
  return model.classes.map((classEntity) => {
    const normalized = normalizeClass(classEntity);
    return {
      id: normalized.id,
      type: "umlClass",
      position: normalized.position,
      style: { width: normalized.width, height: normalized.height },
      data: normalized,
    };
  });
}
