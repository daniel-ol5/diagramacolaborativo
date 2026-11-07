import { ChatApiError, type ChatHistoryQuery } from "./chatApi";

/**
 * Reglas de la busqueda y los filtros del chat (CU23). Funciones puras.
 */

export type ChatFilter = "todos" | "ia" | "resumenes";

export const FILTROS: { id: ChatFilter; etiqueta: string }[] = [
  { id: "todos", etiqueta: "Todos" },
  { id: "ia", etiqueta: "Solo IA" },
  { id: "resumenes", etiqueta: "Resúmenes" },
];

/** Limites del backend para q. */
export const BUSQUEDA_MIN = 2;
export const BUSQUEDA_MAX = 100;

/**
 * El q a enviar, o null si no alcanza para buscar (menos de 2 caracteres
 * utiles). Recorta espacios y corta a 100.
 *
 * Ejemplos:
 *   textoDeBusqueda("  atrib ") -> "atrib"
 *   textoDeBusqueda("a")        -> null
 *   textoDeBusqueda("   ")      -> null
 */
export function textoDeBusqueda(raw: string): string | null {
  const t = raw.trim().slice(0, BUSQUEDA_MAX);
  return t.length >= BUSQUEDA_MIN ? t : null;
}

/**
 * Los parametros del GET para una busqueda/filtro, o null si no hay nada
 * activo (se ve la linea de tiempo normal).
 *
 * Ejemplos:
 *   consultaDe(null, "todos")       -> null
 *   consultaDe(null, "ia")          -> { authorType: "AI" }
 *   consultaDe("atrib", "resumenes") -> { q: "atrib", kind: "SUMMARY" }
 */
export function consultaDe(q: string | null, filtro: ChatFilter): ChatHistoryQuery | null {
  if (q == null && filtro === "todos") return null;
  return {
    ...(q != null ? { q } : {}),
    ...(filtro === "ia" ? { authorType: "AI" as const } : {}),
    ...(filtro === "resumenes" ? { kind: "SUMMARY" as const } : {}),
  };
}

/**
 * "Sin resultados", "1 resultado", "N resultados" o "Más de N resultados"
 * (el servidor no da un total: hasMore dice si hay mas viejos sin cargar).
 */
export function textoDeResultados(n: number, hasMore: boolean): string {
  if (n === 0) return "Sin resultados";
  if (hasMore) return `Más de ${n} resultados`;
  return n === 1 ? "1 resultado" : `${n} resultados`;
}

/** Error de busqueda en espanol. */
export function textoDeErrorDeBusqueda(e: unknown): string {
  if (e instanceof ChatApiError) {
    if (e.status === 403) return "No eres miembro de este proyecto.";
    if (e.status === 400) return "La búsqueda no es válida: usa entre 2 y 100 caracteres.";
    if (e.status === 429) return "Demasiadas búsquedas seguidas; espera un momento.";
  }
  return "No se pudo completar la búsqueda. Revisa tu conexión.";
}
