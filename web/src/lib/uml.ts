import type { Method, Parameter, RelationshipType, Visibility } from "../types/canonical";

/**
 * Solo la ETIQUETA se traduce, nunca el valor: `RelationshipType` viaja tal
 * cual al servidor, al generador y al XMI (el modelo canonico esta
 * congelado). Reportado por Daniel: el desplegable de tipo de relacion
 * mostraba el enum crudo (ASSOCIATION, AGGREGATION, etc.).
 */
export function relationshipTypeLabel(type: RelationshipType): string {
  switch (type) {
    case "ASSOCIATION":
      return "Asociación";
    case "AGGREGATION":
      return "Agregación";
    case "COMPOSITION":
      return "Composición";
    case "MANY_TO_MANY":
      return "Muchos a muchos";
    case "INHERITANCE":
      return "Herencia";
    case "DEPENDENCY":
      return "Dependencia";
  }
}

/** Simbolo UML estandar de visibilidad: + publico, - privado, # protegido, ~ paquete. */
export function visibilitySymbol(visibility: Visibility | undefined): string {
  switch (visibility) {
    case "PUBLIC":
      return "+";
    case "PRIVATE":
      return "-";
    case "PROTECTED":
      return "#";
    case "PACKAGE":
      return "~";
    default:
      return "";
  }
}

function formatParameters(parameters: Parameter[] | undefined): string {
  return (parameters ?? []).map((p) => `${p.name}: ${p.type}`).join(", ");
}

/** Firma UML de un metodo: `+ nombre(param: Tipo): TipoRetorno`. */
export function methodSignature(method: Method): string {
  return `${visibilitySymbol(method.visibility)} ${method.name}(${formatParameters(method.parameters)}): ${method.returnType}`;
}
