import { useState } from "react";
import type { ChatMessage } from "./types";
import { partirPorCoincidencia } from "./highlight";
import { ReviewCard } from "../review/ReviewCard";
import type { ReviewCardMeta } from "../review/types";
import { SummaryCard } from "../summary/SummaryCard";
import type { ChatSummaryCardMeta } from "../summary/types";
import { AiCommandReason, sendAiCommand } from "../../modeling/aiCommandApi";
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
  /** En los resultados de busqueda: lo buscado, para resaltarlo con <mark>. */
  highlight?: string | null;
  /** Diagrama abierto y token para ejecutar una propuesta del Senior en vivo. */
  diagramId?: string | null;
  token?: string;
}

interface PropuestaAplicada {
  message: string;
  operations: string[];
}

/** Memoria de sesión para que al cambiar de filtro o reabrir el panel siga marcando las propuestas ya aplicadas. */
const propuestasAplicadas = new Map<string, PropuestaAplicada>();

interface PropuestaExtraida {
  explicacion: string;
  comando: string | null;
}

/**
 * Extrae la línea "Comando sugerido: ..." que emite el Senior Dev (CU20 / Escenario C)
 * para mostrarla como una propuesta accionable en el chat.
 */
function extraerPropuestaSenior(texto: string): PropuestaExtraida {
  const lineas = texto.split(/\r?\n/);
  const explicacionLineas: string[] = [];
  let comando: string | null = null;

  for (const linea of lineas) {
    const match = linea.match(/^\s*(?:[-*•]\s*)?\*{0,2}Comando\s+sugerido\s*:\*{0,2}\s*(.+?)\s*$/i);
    if (match && match[1]) {
      const limpio = match[1].replace(/^["«“'`]+|["»”'`]+$/g, "").replace(/\.$/, "").trim();
      if (limpio) {
        comando = limpio;
        continue;
      }
    }
    explicacionLineas.push(linea);
  }

  if (!comando) {
    return { explicacion: texto, comando: null };
  }

  const explicacion = explicacionLineas.join("\n").trim();
  return {
    explicacion: explicacion || texto,
    comando,
  };
}

const HORA = new Intl.DateTimeFormat("es", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

/** HH:mm en la hora local; vacio si la fecha no se puede leer. */
function horaLocal(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : HORA.format(d);
}

function resaltarTexto(textoPlano: string, highlight?: string | null) {
  if (!highlight) return textoPlano;
  return partirPorCoincidencia(textoPlano, highlight).map((p, i) =>
    p.m ? (
      <mark key={i} className="message-bubble__match">
        {p.t}
      </mark>
    ) : (
      p.t
    ),
  );
}

/**
 * Un mensaje del chat. El texto se muestra SIEMPRE como texto plano: React
 * lo escapa; nada de dangerouslySetInnerHTML, Markdown ni enlaces clicables.
 * Cualquier `kind` (CHAT, ANSWER, REVIEW, SUMMARY, SYSTEM) se ve igual.
 */
export function MessageBubble({
  message,
  mine,
  status,
  errorText,
  onRetry,
  retryDisabled,
  highlight,
  diagramId,
  token,
}: Props) {
  const hora = horaLocal(message.createdAt);
  const esIa = message.authorType === "AI";
  const propuesta = esIa ? extraerPropuestaSenior(message.text) : { explicacion: message.text, comando: null };
  const texto = resaltarTexto(propuesta.explicacion, highlight);

  const [applying, setApplying] = useState(false);
  const [appliedInfo, setAppliedInfo] = useState<PropuestaAplicada | null>(
    () => propuestasAplicadas.get(message.id) ?? null,
  );
  const [applyError, setApplyError] = useState<string | null>(null);

  const activeDiagramId = diagramId ?? message.diagramId ?? null;

  async function handleApplyProposal() {
    if (!propuesta.comando || !activeDiagramId || !token || applying || appliedInfo) {
      return;
    }
    setApplying(true);
    setApplyError(null);
    try {
      const { body } = await sendAiCommand(
        activeDiagramId,
        token,
        propuesta.comando,
        `propuesta-${message.id}`,
      );
      if (body.applied || body.reason === AiCommandReason.YA_APLICADO) {
        const info: PropuestaAplicada = {
          message: body.message,
          operations: body.operations ?? [],
        };
        propuestasAplicadas.set(message.id, info);
        setAppliedInfo(info);
      } else {
        setApplyError(body.message || "No se pudo aplicar la propuesta al diagrama.");
      }
    } catch (err) {
      setApplyError(err instanceof Error ? err.message : "Error de red al aplicar la propuesta.");
    } finally {
      setApplying(false);
    }
  }

  if (message.authorType === "SYSTEM") {
    return (
      <li className="message-bubble message-bubble--system" data-chat-id={message.id}>
        <p className="message-bubble__text">{texto}</p>
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

  if (message.kind === "SUMMARY") {
    const meta = (message.meta as ChatSummaryCardMeta | undefined) ?? ({} as ChatSummaryCardMeta);
    return (
      <li className="message-bubble message-bubble--summary">
        <SummaryCard meta={meta} textFallback={message.text} />
        {hora && (
          <time className="message-bubble__time" dateTime={message.createdAt}>
            {hora}
          </time>
        )}
      </li>
    );
  }

  const variante =
    (esIa ? " message-bubble--ai" : mine ? " message-bubble--mine" : "") +
    (status === "sending" ? " message-bubble--sending" : "") +
    (status === "failed" ? " message-bubble--failed" : "");

  return (
    <li className={`message-bubble${variante}`} data-chat-id={message.id}>
      <div className="message-bubble__meta">
        {esIa ? (
          <span className="message-bubble__author message-bubble__author--ai">
            {message.addressedToName ? `Senior IA · para ${message.addressedToName}` : "Senior IA"}
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
      <p className="message-bubble__text">{texto}</p>
      {esIa && propuesta.comando && (
        <div className="message-bubble__proposal">
          <div className="message-bubble__proposal-cmd">
            <span className="message-bubble__proposal-label">Propuesta:</span>{" "}
            <span>{resaltarTexto(propuesta.comando, highlight)}</span>
          </div>
          {activeDiagramId && token && (
            <button
              type="button"
              className={`message-bubble__proposal-btn${
                appliedInfo ? " message-bubble__proposal-btn--applied" : ""
              }`}
              onClick={() => void handleApplyProposal()}
              disabled={applying || Boolean(appliedInfo)}
            >
              {applying
                ? "Aplicando propuesta…"
                : appliedInfo
                  ? "Propuesta aplicada al diagrama"
                  : "Aplicar propuesta del Senior"}
            </button>
          )}
          {appliedInfo && (
            <div className="message-bubble__proposal-feedback message-bubble__proposal-feedback--applied" role="status">
              {appliedInfo.operations.length > 0
                ? appliedInfo.operations.join(" ")
                : appliedInfo.message}
            </div>
          )}
          {applyError && (
            <div className="message-bubble__proposal-feedback message-bubble__proposal-feedback--error" role="alert">
              {applyError}
            </div>
          )}
        </div>
      )}
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
