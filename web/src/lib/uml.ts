import type { Method, Parameter, Visibility } from "../types/canonical";

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
