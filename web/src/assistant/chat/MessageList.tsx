import { useLayoutEffect, useRef } from "react";
import type { ChatMessage } from "./types";
import type { OutgoingMessage } from "./chatStore";
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
}

/** Distancia al fondo (px) por debajo de la cual se considera "estoy abajo del todo". */
const UMBRAL_FONDO = 48;

/**
 * Lista con auto-scroll al final, SALVO que el usuario haya subido a leer:
 * en ese caso los mensajes nuevos no lo arrastran hacia abajo. Al enviar uno
 * propio si baja siempre.
 * role="log" + aria-live="polite": los lectores de pantalla anuncian los
 * mensajes nuevos sin interrumpir.
 */
export function MessageList({ messages, pending, me, onRetry, retryDisabled }: Props) {
  const ref = useRef<HTMLDivElement | null>(null);
  const pegadoAlFondo = useRef(true);
  const pendientesAntes = useRef(pending.length);

  function alDesplazar() {
    const el = ref.current;
    if (!el) return;
    pegadoAlFondo.current = el.scrollHeight - el.scrollTop - el.clientHeight <= UMBRAL_FONDO;
  }

  useLayoutEffect(() => {
    const el = ref.current;
    const envieUnoNuevo = pending.length > pendientesAntes.current;
    pendientesAntes.current = pending.length;
    if (el && (pegadoAlFondo.current || envieUnoNuevo)) {
      el.scrollTop = el.scrollHeight;
      pegadoAlFondo.current = true;
    }
  }, [messages, pending]);

  return (
    <div
      ref={ref}
      className="message-list"
      role="log"
      aria-live="polite"
      aria-relevant="additions"
      aria-label="Mensajes del chat"
      onScroll={alDesplazar}
      tabIndex={0}
    >
      <ol className="message-list__items">
        {messages.map((m) => (
          <MessageBubble
            key={m.id}
            message={m}
            mine={m.authorType === "USER" && m.authorId != null && m.authorId === me.userId}
          />
        ))}
        {pending.map((o) => (
          <MessageBubble
            key={`local:${o.clientMessageId}`}
            message={{
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
            }}
            mine
            status={o.status}
            errorText={o.error}
            onRetry={() => onRetry(o.clientMessageId)}
            retryDisabled={retryDisabled}
          />
        ))}
      </ol>
    </div>
  );
}
