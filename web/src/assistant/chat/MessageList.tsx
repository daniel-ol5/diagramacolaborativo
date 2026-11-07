import { Fragment, useLayoutEffect, useRef } from "react";
import type { ChatMessage } from "./types";
import { useChatStore, type OutgoingMessage } from "./chatStore";
import { intercalarDias } from "./dayLabels";
import { MessageBubble } from "./MessageBubble";
import "./MessageList.css";

interface Me {
  userId: string;
  name: string;
  color?: string;
}

interface Props {
  messages: ChatMessage[];
  /** Propios sin eco ("enviando" / "no se envió"), siempre al final. */
  pending: OutgoingMessage[];
  me: Me;
  onRetry: (clientMessageId: string) => void;
  retryDisabled: boolean;
  /** Hay mensajes mas viejos que el primero de `messages`. */
  hasOlder: boolean;
  loadingOlder: boolean;
  olderError: string | null;
  onLoadOlder: () => void;
  /**
   * Linea de tiempo normal (true, por defecto): informa al store si esta al
   * final (no leidos / marcar leido). En los resultados de busqueda, false.
   */
  trackReadPosition?: boolean;
  /** Posicion a restaurar al montarse (volver de la busqueda). */
  initialPosition?: ListPosition | null;
  /** Se llama al desmontarse con la posicion actual. */
  onLeave?: (position: ListPosition) => void;
  /** Lo buscado, para resaltarlo en cada mensaje. */
  highlight?: string | null;
  /** Nombre accesible de la lista. */
  label?: string;
  /**
   * Id del mensaje ante el que va la linea "Mensajes nuevos" (o null). Al
   * aparecer por primera vez, la lista se desplaza hasta la linea en vez de
   * al final.
   */
  newMarkerId?: string | null;
  /** Diagrama activo y token para aplicar propuestas del Senior desde el chat. */
  diagramId?: string | null;
  token?: string;
}

/** Donde estaba el usuario: al final, o un mensaje de referencia y su distancia al borde superior. */
export interface ListPosition {
  atBottom: boolean;
  anchorId: string | null;
  offset: number;
}

/** Distancia al fondo (px) por debajo de la cual se considera "estoy abajo del todo". */
const UMBRAL_FONDO = 48;
/** Distancia al tope (px) por debajo de la cual se piden los anteriores. */
const UMBRAL_TOPE = 80;
/** Aire (px) sobre la linea "Mensajes nuevos" al desplazarse hasta ella. */
const MARGEN_MARCADOR = 8;

/** Un mensaje confirmado o uno propio pendiente, ya como ChatMessage para dibujar. */
interface Fila {
  message: ChatMessage;
  pending?: OutgoingMessage;
}

/**
 * Lista con auto-scroll al final, SALVO que el usuario haya subido a leer:
 * en ese caso los mensajes nuevos no lo arrastran hacia abajo. Al enviar uno
 * propio si baja siempre.
 *
 * Cargar anteriores: al acercarse al tope (o con el boton "Cargar
 * anteriores", para teclado y lectores de pantalla). Lo que se antepone no
 * mueve lo que se estaba viendo: se toma como ancla el primer mensaje
 * cargado y su distancia al borde superior visible, y tras dibujar se
 * corrige scrollTop para que quede en el mismo lugar. El ancla es un
 * elemento (no la altura total), asi que un mensaje en vivo que llega abajo
 * mientras tanto no la corre.
 *
 * Separadores de dia ("Hoy", "Ayer", "5 nov 2026") antes del primer mensaje
 * de cada dia, en hora local.
 *
 * Linea "Mensajes nuevos" (newMarkerId) antes del primer no leido de otro;
 * al abrir el panel con no leidos, la lista arranca en ella y no al final.
 *
 * role="log" + aria-live="polite": los lectores de pantalla anuncian los
 * mensajes nuevos sin interrumpir.
 */
export function MessageList({
  messages,
  pending,
  me,
  onRetry,
  retryDisabled,
  hasOlder,
  loadingOlder,
  olderError,
  onLoadOlder,
  trackReadPosition = true,
  initialPosition = null,
  onLeave,
  highlight = null,
  label = "Mensajes del chat",
  newMarkerId = null,
  diagramId = null,
  token,
}: Props) {
  const ref = useRef<HTMLDivElement | null>(null);
  const pegadoAlFondo = useRef(initialPosition ? initialPosition.atBottom : true);
  const pendientesAntes = useRef(pending.length);
  const primerSeqAntes = useRef<number | null>(messages[0]?.seq ?? null);
  /** El primer mensaje cargado y su distancia (px) al borde superior visible de la lista. */
  const ancla = useRef<{ id: string; desde: number } | null>(null);
  /** Se usa una sola vez, en el primer dibujo. */
  const porRestaurar = useRef(initialPosition);
  /** Ya se fue una vez a la linea "Mensajes nuevos" (o se restauro una posicion): no volver a saltar. */
  const fueAlMarcador = useRef(initialPosition != null);
  const alSalir = useRef(onLeave);
  alSalir.current = onLeave;

  function informarFondo(alFinal: boolean) {
    // Para no leidos y "marcar leido" (el store ignora si no cambio).
    if (trackReadPosition) useChatStore.getState().setAtBottom(alFinal);
  }

  function nodoDe(el: HTMLElement, id: string): Element | null {
    return el.querySelector(`[data-chat-id="${CSS.escape(id)}"]`);
  }

  function medirAncla() {
    const el = ref.current;
    const primero = messages[0];
    const nodo = el && primero ? nodoDe(el, primero.id) : null;
    ancla.current =
      el && primero && nodo
        ? { id: primero.id, desde: nodo.getBoundingClientRect().top - el.getBoundingClientRect().top }
        : null;
  }

  function actualizarFondo(el: HTMLElement) {
    pegadoAlFondo.current = el.scrollHeight - el.scrollTop - el.clientHeight <= UMBRAL_FONDO;
    informarFondo(pegadoAlFondo.current);
  }

  function alDesplazar() {
    const el = ref.current;
    if (!el) return;
    actualizarFondo(el);
    medirAncla();
    // Tras un error no se reintenta solo: queda el boton.
    if (el.scrollTop <= UMBRAL_TOPE && hasOlder && !loadingOlder && !olderError) onLoadOlder();
  }

  // medirAncla lee `messages` de este render: es lo que se quiere.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;

    // Volver de la busqueda: el mensaje de referencia donde estaba. Si ya no
    // esta (o estaba al final), al final.
    const r = porRestaurar.current;
    porRestaurar.current = null;
    if (r && !r.atBottom) {
      const n = r.anchorId ? nodoDe(el, r.anchorId) : null;
      if (n) {
        el.scrollTop += n.getBoundingClientRect().top - el.getBoundingClientRect().top - r.offset;
        actualizarFondo(el);
        medirAncla();
        return;
      }
      pegadoAlFondo.current = true;
    }

    const primerSeq = messages[0]?.seq ?? null;
    const antepuso = primerSeq != null && primerSeqAntes.current != null && primerSeq < primerSeqAntes.current;
    primerSeqAntes.current = primerSeq;
    const envieUnoNuevo = pending.length > pendientesAntes.current;
    pendientesAntes.current = pending.length;

    // Al abrir con no leidos: la linea "Mensajes nuevos" arriba, no el final.
    if (newMarkerId && !fueAlMarcador.current) {
      const linea = el.querySelector("[data-new-marker]");
      if (linea) {
        fueAlMarcador.current = true;
        el.scrollTop += linea.getBoundingClientRect().top - el.getBoundingClientRect().top - MARGEN_MARCADOR;
        actualizarFondo(el);
        medirAncla();
        return;
      }
    }

    const a = ancla.current;
    const nodo = antepuso && a && !envieUnoNuevo ? nodoDe(el, a.id) : null;
    if (a && nodo) {
      // Mantener el ancla donde estaba: sin saltos.
      el.scrollTop += nodo.getBoundingClientRect().top - el.getBoundingClientRect().top - a.desde;
      actualizarFondo(el);
    } else if (pegadoAlFondo.current || envieUnoNuevo) {
      el.scrollTop = el.scrollHeight;
      pegadoAlFondo.current = true;
      informarFondo(true);
    }
    medirAncla();
  }, [messages, pending, newMarkerId]);

  // Al desmontarse (p. ej. al entrar a la busqueda) entrega donde estaba: el
  // primer mensaje visible y su distancia al borde superior. En la limpieza
  // de un layout effect el DOM todavia esta puesto.
  useLayoutEffect(
    () => () => {
      const el = ref.current;
      if (!el || !alSalir.current) return;
      const tope = el.getBoundingClientRect().top;
      let anchorId: string | null = null;
      let offset = 0;
      for (const n of Array.from(el.querySelectorAll("[data-chat-id]"))) {
        const rect = n.getBoundingClientRect();
        if (rect.bottom > tope) {
          anchorId = n.getAttribute("data-chat-id");
          offset = rect.top - tope;
          break;
        }
      }
      alSalir.current({ atBottom: pegadoAlFondo.current, anchorId, offset });
    },
    [],
  );

  const filas: Fila[] = messages.map((m) => ({ message: m }));
  for (const o of pending) {
    filas.push({
      pending: o,
      message: {
        id: `local:${o.clientMessageId}`,
        projectId: "",
        seq: Number.POSITIVE_INFINITY,
        authorType: "USER",
        authorId: me.userId,
        authorName: me.name,
        authorColor: me.color,
        clientMessageId: o.clientMessageId,
        kind: "CHAT",
        text: o.text,
        createdAt: o.createdAt,
      },
    });
  }
  const conDias = intercalarDias(filas, (f) => f.message.createdAt);

  return (
    <div
      ref={ref}
      className="message-list"
      role="log"
      aria-live="polite"
      aria-relevant="additions"
      aria-label={label}
      onScroll={alDesplazar}
      tabIndex={0}
    >
      {(hasOlder || olderError) && (
        <div className="message-list__older">
          <button
            type="button"
            className="message-list__older-btn"
            onClick={() => {
              medirAncla();
              onLoadOlder();
            }}
            disabled={loadingOlder}
          >
            {loadingOlder ? "Cargando anteriores…" : "Cargar anteriores"}
          </button>
          {olderError && (
            <p className="message-list__older-error" role="alert">
              {olderError}
            </p>
          )}
        </div>
      )}
      <ol className="message-list__items">
        {conDias.map((x) => {
          if (x.tipo === "dia") {
            return (
              <li key={`dia:${x.clave}`} className="message-list__day" role="separator" aria-label={x.etiqueta}>
                <span className="message-list__day-label">{x.etiqueta}</span>
              </li>
            );
          }
          const { message: m, pending: p } = x.item;
          return p ? (
            <MessageBubble
              key={m.id}
              message={m}
              mine
              status={p.status}
              errorText={p.error}
              onRetry={() => onRetry(p.clientMessageId)}
              retryDisabled={retryDisabled}
            />
          ) : (
            <Fragment key={m.id}>
              {m.id === newMarkerId && (
                <li className="message-list__new" role="separator" aria-label="Mensajes nuevos" data-new-marker="">
                  <span className="message-list__new-label">Mensajes nuevos</span>
                </li>
              )}
              <MessageBubble
                message={m}
                mine={m.authorType === "USER" && m.authorId != null && m.authorId === me.userId}
                highlight={highlight}
                diagramId={diagramId}
                token={token}
              />
            </Fragment>
          );
        })}
      </ol>
    </div>
  );
}
