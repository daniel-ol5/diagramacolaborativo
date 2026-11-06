import type { ChatMessage } from "./types";
import type { ChatReadState } from "./chatApi";

/**
 * Reglas de "no leidos" del chat (CU23). Funciones puras: no leen el store
 * ni el DOM, todo entra por parametro. El proyecto no tiene framework de
 * pruebas; los ejemplos de cada una sirven de casos de prueba.
 */

/** Si el usuario esta mirando el final de la linea de tiempo ahora mismo. */
export interface Vista {
  panelOpen: boolean;
  /** La lista esta a <= 48 px del fondo. */
  atBottom: boolean;
  /** document.visibilityState === "visible". */
  visible: boolean;
}

export function viendoElFinal(v: Vista): boolean {
  return v.panelOpen && v.atBottom && v.visible;
}

/** Un mensaje es "de otro" si no tiene authorId (IA, SYSTEM) o si es de otra persona. */
export function esDeOtro(m: Pick<ChatMessage, "authorId">, myUserId: string | null): boolean {
  return m.authorId == null || m.authorId !== myUserId;
}

/**
 * Si un mensaje que llego EN VIVO por el topic suma 1 al contador.
 *
 * Suma solo si:
 * - no estaba ya en el store (un eco repetido o uno que ya trajo el historial no cuenta dos veces);
 * - es de otro (IA y SYSTEM incluidos);
 * - su seq es mayor que el lastSeq de la ultima respuesta de /chat/read: lo
 *   que esta por debajo el servidor ya lo conto en unreadCount;
 * - el usuario NO esta viendo el final (panel cerrado, lista subida o pestaña oculta).
 *
 * Ejemplos (yo = "u1", readLastSeq = 10, panel cerrado):
 *   seq 11 de "u2"                  -> true
 *   seq 11 de la IA (sin authorId)  -> true
 *   seq 11 mio ("u1")               -> false
 *   seq 9 de "u2"                   -> false (ya contado por el servidor)
 *   seq 11 de "u2" ya en el store   -> false
 *   seq 11 de "u2" con panel abierto, al final y pestaña visible -> false
 *   readLastSeq = null (todavia no respondio /chat/read) -> cuenta; la
 *   respuesta posterior recalcula el total (ver noLeidosDesdeServidor).
 */
export function debeContarNoLeido(
  m: Pick<ChatMessage, "seq" | "authorId">,
  ctx: { myUserId: string | null; yaEstaba: boolean; readLastSeq: number | null; vista: Vista },
): boolean {
  if (ctx.yaEstaba) return false;
  if (!esDeOtro(m, ctx.myUserId)) return false;
  if (ctx.readLastSeq != null && m.seq <= ctx.readLastSeq) return false;
  return !viendoElFinal(ctx.vista);
}

/**
 * El contador despues de una respuesta de /chat/read (GET o PUT).
 *
 * El servidor cuenta hasta su lastSeq. Lo que ya esta en el store con seq
 * mayor (llego por el topic despues de que el servidor armo la respuesta) se
 * suma aparte, salvo que el usuario este viendo el final: en ese caso el
 * proximo PUT lo marca leido.
 *
 * Ejemplos (yo = "u1"):
 *   resp {unreadCount: 3, lastSeq: 20}, store hasta 20         -> 3
 *   resp {unreadCount: 3, lastSeq: 20}, store con 21 de "u2"   -> 4 (sin mirar el final)
 *   idem pero mirando el final                                 -> 3
 *   resp {unreadCount: 0, lastSeq: 20}, store con 21 mio       -> 0
 */
export function noLeidosDesdeServidor(
  resp: ChatReadState,
  messages: ChatMessage[],
  myUserId: string | null,
  vista: Vista,
): number {
  if (viendoElFinal(vista)) return resp.unreadCount;
  let extra = 0;
  for (let i = messages.length - 1; i >= 0 && messages[i].seq > resp.lastSeq; i--) {
    if (esDeOtro(messages[i], myUserId)) extra++;
  }
  return resp.unreadCount + extra;
}

/**
 * Hasta que seq marcar leido, o null si no corresponde enviar el PUT.
 *
 * Se marca solo si el usuario esta viendo el final (panel abierto, lista al
 * final y pestaña visible) y el mayor seq de la linea de tiempo normal supera
 * tanto el ultimo lastReadSeq conocido como lo que ya hay en vuelo.
 *
 * Ejemplos (vista = mirando el final):
 *   maxSeq 30, lastReadSeq 25, enVuelo null -> 30
 *   maxSeq 30, lastReadSeq 30               -> null (ya leido)
 *   maxSeq 30, lastReadSeq 25, enVuelo 30   -> null (ya se pidio)
 *   maxSeq 31, lastReadSeq 25, enVuelo 30   -> 31
 *   maxSeq null (sin mensajes)              -> null
 *   panel cerrado / lista subida / pestaña oculta -> null
 */
export function seqParaMarcarLeido(ctx: {
  vista: Vista;
  maxSeq: number | null;
  lastReadSeq: number | null;
  enVuelo: number | null;
}): number | null {
  if (!viendoElFinal(ctx.vista) || ctx.maxSeq == null) return null;
  if (ctx.lastReadSeq != null && ctx.maxSeq <= ctx.lastReadSeq) return null;
  if (ctx.enVuelo != null && ctx.maxSeq <= ctx.enVuelo) return null;
  return ctx.maxSeq;
}

/**
 * Donde va la linea "Mensajes nuevos": el id del primer mensaje DE OTRO con
 * seq mayor que el lastReadSeq que se tenia al abrir el panel. null si no hay.
 *
 * Si los no leidos empiezan antes de lo cargado, la linea va antes del
 * primero cargado que cumpla; al cargar anteriores se recalcula y sube.
 *
 * Ejemplos (yo = "u1", lastReadSeq al abrir = 10):
 *   [9 de u2, 11 mio, 12 de u2, 13 IA] -> id del 12 (el 11 es mio)
 *   [9 de u2, 10 de u2]                -> null
 *   [11 de la IA, 12 de u2]            -> id del 11
 *   lastReadSeq al abrir = null        -> null (no habia nada que marcar)
 */
export function idDelPrimerNoLeido(
  messages: Pick<ChatMessage, "id" | "seq" | "authorId">[],
  lastReadSeqAlAbrir: number | null,
  myUserId: string | null,
): string | null {
  if (lastReadSeqAlAbrir == null) return null;
  const m = messages.find((x) => x.seq > lastReadSeqAlAbrir && esDeOtro(x, myUserId));
  return m ? m.id : null;
}

/** Texto de la insignia: "99+" como tope. */
export function textoInsignia(n: number): string {
  return n > 99 ? "99+" : String(n);
}

/** "1 mensaje sin leer" / "N mensajes sin leer". */
export function etiquetaNoLeidos(n: number): string {
  return n === 1 ? "1 mensaje sin leer" : `${n} mensajes sin leer`;
}
