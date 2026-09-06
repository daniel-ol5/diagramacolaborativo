import type { ClassEntity } from "../types/canonical";

/**
 * D-16: §7 no exige "visibility" en una clase ni le declara default (a
 * diferencia de Attribute/Method, donde SI es requerido). Es asi a proposito:
 * una entidad JPA generada siempre es publica, asi que el campo es decorativo
 * — solo afecta si algun dia se dibuja un simbolo en el nodo. La normalizacion
 * va en un solo lugar (aca, en el borde de entrada del modelo), igual que el
 * record Java ClassEntity la hace en su constructor compacto: el resto del
 * codigo no se entera de que el campo era opcional.
 */
export function normalizeClass(classEntity: ClassEntity): ClassEntity {
  return {
    ...classEntity,
    visibility: classEntity.visibility ?? "PUBLIC",
  };
}
