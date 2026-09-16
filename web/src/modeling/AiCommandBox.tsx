import { useState, type FormEvent } from "react";
import { sendAiCommand, type AiCommandResult } from "./aiCommandApi";
import "./AiCommandBox.css";

interface Props {
  diagramId: string;
  token: string;
  onClose: () => void;
}

interface Entrada {
  command: string;
  httpStatus: number;
  result: AiCommandResult;
}

/** El pedido exacto que el plan (§19) usa como pregunta del tribunal para el guardarraíl -- un clic alcanza para mostrarlo en una demo. */
const EJEMPLO_GUARDARRAIL = "Créame un sistema para un banco";

/**
 * CU16: modelado por comando de texto (la voz entra por el mismo cuadro --
 * el reconocimiento de voz del navegador produce texto, así que de acá
 * para adentro no hay ninguna diferencia, ver AiCommandRequest.java).
 *
 * El diagrama en sí NO se actualiza desde acá: las mutaciones que un
 * comando aplica llegan por la misma difusión STOMP de siempre
 * (`/topic/diagrams/{id}`, ya manejada por useCollaboration) — este
 * componente sólo manda el comando y muestra la conversación.
 */
export function AiCommandBox({ diagramId, token, onClose }: Props) {
  const [command, setCommand] = useState("");
  const [history, setHistory] = useState<Entrada[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function enviar(texto: string) {
    const trimmed = texto.trim();
    if (!trimmed || sending) return;
    setSending(true);
    setError(null);
    try {
      const { httpStatus, body } = await sendAiCommand(diagramId, token, trimmed, crypto.randomUUID());
      setHistory((prev) => [...prev, { command: trimmed, httpStatus, result: body }]);
      setCommand("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSending(false);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void enviar(command);
  }

  return (
    <div className="ai-command">
      <div className="ai-command__header">
        <span>Comando</span>
        <button type="button" className="ai-command__close" onClick={onClose} aria-label="Cerrar">
          ✕
        </button>
      </div>
      <div className="ai-command__history">
        {history.length === 0 && (
          <>
            <p className="ai-command__empty">
              Escribí lo que querés cambiar en el diagrama, por ejemplo "agregá la clase Factura
              con atributo total decimal".
            </p>
            <button type="button" className="ai-command__example" onClick={() => void enviar(EJEMPLO_GUARDARRAIL)}>
              Probar el guardarraíl: "{EJEMPLO_GUARDARRAIL}"
            </button>
          </>
        )}
        {history.map((entrada, i) => (
          <div key={i} className="ai-command__entry">
            <div className="ai-command__command">{entrada.command}</div>
            {entrada.result.applied ? (
              <div className="ai-command__response ai-command__response--applied">
                {entrada.result.message}
                {entrada.result.operations.length > 0 && (
                  <ul>
                    {entrada.result.operations.map((op, j) => (
                      <li key={j}>{op}</li>
                    ))}
                  </ul>
                )}
              </div>
            ) : (
              <div
                className={`ai-command__response ${
                  entrada.httpStatus === 502 ? "ai-command__response--failed" : "ai-command__response--rejected"
                }`}
              >
                {/* Texto tal cual lo manda el servidor -- ni una palabra reescrita (coincide caracter a caracter con §RF-02.6 y con un test). */}
                {entrada.result.message}
              </div>
            )}
          </div>
        ))}
        {error && <p className="ai-command__response ai-command__response--failed">⚠ {error}</p>}
      </div>
      <form className="ai-command__form" onSubmit={handleSubmit}>
        <input
          type="text"
          value={command}
          onChange={(e) => setCommand(e.target.value)}
          placeholder="Escribí un comando…"
          maxLength={500}
          disabled={sending}
          autoFocus
        />
        <button type="submit" disabled={sending || !command.trim()}>
          {sending ? "Enviando…" : "Enviar"}
        </button>
      </form>
    </div>
  );
}
