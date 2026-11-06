/**
 * Separadores de dia de la lista del chat (CU23). Funciones puras, en hora
 * LOCAL del navegador. `ahora` entra por parametro para poder probarlas.
 *
 * Los meses van en un arreglo fijo y no con Intl: segun la version del
 * motor, "es" da "sept" o "sep", "nov" o "nov.", y la etiqueta cambiaria de
 * una maquina a otra.
 */

const MESES = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"];

function dosDigitos(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

function claveDeFecha(d: Date): string {
  return `${d.getFullYear()}-${dosDigitos(d.getMonth() + 1)}-${dosDigitos(d.getDate())}`;
}

/**
 * El dia local de un ISO-8601, como "AAAA-MM-DD". null si la fecha no es valida.
 *
 * Ejemplo (en UTC-4): claveDeDia("2026-11-06T02:30:00Z") -> "2026-11-05"
 */
export function claveDeDia(iso: string): string | null {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : claveDeFecha(d);
}

/**
 * "Hoy", "Ayer" o "5 nov 2026" (hora local).
 *
 * Ejemplos con ahora = 6 nov 2026 10:00 local:
 *   etiquetaDeDia(<6 nov 2026 00:05 local>)  -> "Hoy"
 *   etiquetaDeDia(<5 nov 2026 23:59 local>)  -> "Ayer"
 *   etiquetaDeDia(<4 nov 2026 12:00 local>)  -> "4 nov 2026"
 *   etiquetaDeDia(<1 ene 2025>)              -> "1 ene 2025"
 *   etiquetaDeDia("no es fecha")             -> ""
 * "Ayer" se calcula restando un dia de calendario (no 24 h), asi que los
 * cambios de horario no lo corren.
 */
export function etiquetaDeDia(iso: string, ahora: Date = new Date()): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const clave = claveDeFecha(d);
  if (clave === claveDeFecha(ahora)) return "Hoy";
  const ayer = new Date(ahora.getFullYear(), ahora.getMonth(), ahora.getDate() - 1);
  if (clave === claveDeFecha(ayer)) return "Ayer";
  return `${d.getDate()} ${MESES[d.getMonth()]} ${d.getFullYear()}`;
}

export type ItemConDia<T> = { tipo: "dia"; clave: string; etiqueta: string } | { tipo: "item"; item: T };

/**
 * Intercala un separador antes del primer elemento de cada dia. Los
 * elementos llegan ya ordenados; una fecha invalida no abre dia nuevo.
 *
 * Ejemplo (ahora = 6 nov): [5 nov 10:00, 5 nov 11:00, 6 nov 09:00]
 *   -> [dia "Ayer", item, item, dia "Hoy", item]
 */
export function intercalarDias<T>(
  items: T[],
  fechaDe: (item: T) => string,
  ahora: Date = new Date(),
): ItemConDia<T>[] {
  const salida: ItemConDia<T>[] = [];
  let anterior: string | null = null;
  for (const item of items) {
    const iso = fechaDe(item);
    const clave = claveDeDia(iso);
    if (clave != null && clave !== anterior) {
      salida.push({ tipo: "dia", clave, etiqueta: etiquetaDeDia(iso, ahora) });
      anterior = clave;
    }
    salida.push({ tipo: "item", item });
  }
  return salida;
}
