import type { ChatMessage } from "./types";
import { ReviewCard } from "../review/ReviewCard";
import type { ReviewCardMeta } from "../review/types";
import "./MessageBubble.css";

interface Props {
  message: ChatMessage;
  mine: boolean;
  /** Solo para propios sin eco: "enviando" o "no se envió". */
  status?: "sending" | "failed";
  /** Motivo del rechazo, si lo hay. */
  errorText?: string;
  onRetry?: () => void;
  /** Sin conexion no se puede reintentar. */
  retryDisabled?: boolean;
}

const HORA = new Intl.DateTimeFormat("es", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

/** HH:mm en la hora local; vacio si la fecha no se puede leer. */
function horaLocal(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : HORA.format(d);
}

/**
 * Un mensaje del chat. El texto se muestra SIEMPRE como texto plano: React
 * lo escapa; nada de dangerouslySetInnerHTML, Markdown ni enlaces clicables.
 * Cualquier `kind` (CHAT, ANSWER, REVIEW, SUMMARY, SYSTEM) se ve igual.
 */
export function MessageBubble({ message, mine, status, errorText, onRetry, retryDisabled }: Props) {
  const hora = horaLocal(message.createdAt);

  if (message.authorType === "SYSTEM") {
    return (
      <li className="message-bubble message-bubble--system">
        <p className="message-bubble__text">{message.text}</p>
        {hora && (
          <time className="message-bubble__time" dateTime={message.createdAt}>
            {hora}
          </time>
        )}
      </li>
    );
  }

  if (message.kind === "REVIEW") {
    const meta = (message.meta as ReviewCardMeta | undefined) ?? ({} as ReviewCardMeta);
    return (
      <li className="message-bubble message-bubble--review">
        <ReviewCard meta={meta} textFallback={message.text} />
        {hora && (
          <time className="message-bubble__time" dateTime={message.createdAt}>
            {hora}
          </time>
        )}
      </li>
    );
  }

  const esIa = message.authorType === "AI";
  const variante =
    (esIa ? " message-bubble--ai" : mine ? " message-bubble--mine" : "") +
    (status === "sending" ? " message-bubble--sending" : "") +
    (status === "failed" ? " message-bubble--failed" : "");

  return (
    <li className={`message-bubble${variante}`}>
      <div className="message-bubble__meta">
        {esIa ? (
          <span className="message-bubble__author message-bubble__author--ai">
            {message.addressedToName ? `IA · para ${message.addressedToName}` : "IA"}
          </span>
        ) : (
          <span className="message-bubble__author">
            <span
              className="message-bubble__dot"
              style={message.authorColor ? { background: message.authorColor } : undefined}
              aria-hidden="true"
            />
            {message.authorName}
          </span>
        )}
        {hora && (
          <time className="message-bubble__time" dateTime={message.createdAt}>
            {hora}
          </time>
        )}
      </div>
      <p className="message-bubble__text">{message.text}</p>
      {status === "sending" && <p className="message-bubble__status">Enviando…</p>}
      {status === "failed" && (
        <div className="message-bubble__status message-bubble__status--failed">
          <span>
            No se envió{errorText ? ` · ${errorText}` : ""}
          </span>
          {onRetry && (
            <button type="button" className="message-bubble__retry" onClick={onRetry} disabled={retryDisabled}>
              Reintentar
            </button>
          )}
        </div>
      )}
    </li>
  );
}
