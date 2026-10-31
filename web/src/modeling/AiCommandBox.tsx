import { useState, useEffect, type FormEvent } from "react";
import { sendAiCommand, type AiCommandResult } from "./aiCommandApi";
import { useSpeechRecognition } from "./useSpeechRecognition";
import "./AiCommandBox.css";

interface Props {
  diagramId: string;
  token: string;
  onClose: () => void;
  initialListening?: boolean;
}

interface Entrada {
  command: string;
  httpStatus: number;
  result: AiCommandResult;
}

/** El pedido exacto que el plan (§19) usa como pregunta del tribunal para el guardarraíl -- un clic alcanza para mostrarlo en una demo. */
const EJEMPLO_GUARDARRAIL = "Créame un sistema para un banco";

/**
 * CU16: modelado por comando de texto y voz.
 *
 * El reconocimiento de voz del navegador Web Speech API captura el audio del
 * micrófono de la laptop, transcribe en tiempo real a texto en español, y lo
 * envía a través del mismo endpoint CU16 de IA.
 *
 * El diagrama en sí NO se actualiza desde acá: las mutaciones que un
 * comando aplica llegan por la misma difusión STOMP de siempre
 * (`/topic/diagrams/{id}`, ya manejada por useCollaboration) — este
 * componente sólo manda el comando y muestra la conversación.
 */
export function AiCommandBox({ diagramId, token, onClose, initialListening }: Props) {
  const [command, setCommand] = useState("");
  const [history, setHistory] = useState<Entrada[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [autoSendVoice, setAutoSendVoice] = useState(false);

  const {
    isSupported,
    isListening,
    transcript,
    error: voiceError,
    startListening,
    stopListening,
  } = useSpeechRecognition();

  // Si se solicita arrancar escuchando de inmediato
  useEffect(() => {
    if (initialListening && isSupported && !isListening) {
      handleToggleVoice();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialListening, isSupported]);

  // Actualizar el texto del input en tiempo real a medida que el usuario habla
  useEffect(() => {
    if (isListening && transcript) {
      setCommand(transcript);
    }
  }, [isListening, transcript]);

  function handleToggleVoice() {
    if (isListening) {
      stopListening();
    } else {
      startListening((finalText) => {
        setCommand(finalText);
        if (autoSendVoice && finalText.trim()) {
          void enviar(finalText.trim());
        }
      });
    }
  }

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
        <span>Comando (Texto o Micrófono)</span>
        <button type="button" className="ai-command__close" onClick={onClose} aria-label="Cerrar">
          ✕
        </button>
      </div>
      <div className="ai-command__history">
        {history.length === 0 && (
          <>
            <p className="ai-command__empty">
              Escribí o hablá por el micrófono lo que querés cambiar en el diagrama, por ejemplo:
              "agregá la clase Factura con atributo total decimal".
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
        {voiceError && <p className="ai-command__response ai-command__response--failed">⚠ {voiceError}</p>}
      </div>

      {isListening && (
        <div className="ai-command__listening-bar" role="status" aria-live="polite">
          <span className="ai-command__listening-pulse" />
          <span>Escuchando micrófono de la laptop… hablá tu comando</span>
        </div>
      )}

      <form className="ai-command__form" onSubmit={handleSubmit}>
        <div className="ai-command__input-group">
          <input
            type="text"
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            placeholder={isListening ? "Escuchando... hablá ahora" : "Escribí o dictá un comando…"}
            maxLength={500}
            disabled={sending}
            autoFocus
          />
          {isSupported && (
            <button
              type="button"
              className={`ai-command__mic-btn${isListening ? " ai-command__mic-btn--active" : ""}`}
              onClick={handleToggleVoice}
              title={isListening ? "Detener micrófono" : "Activar micrófono de la laptop"}
              aria-label={isListening ? "Detener micrófono" : "Activar micrófono de la laptop"}
              disabled={sending}
            >
              {isListening ? "⏹" : "🎤"}
            </button>
          )}
        </div>
        <button type="submit" disabled={sending || !command.trim()}>
          {sending ? "Enviando…" : "Enviar"}
        </button>
      </form>
      {isSupported && (
        <label className="ai-command__auto-send-toggle">
          <input
            type="checkbox"
            checked={autoSendVoice}
            onChange={(e) => setAutoSendVoice(e.target.checked)}
          />
          <span>Enviar automáticamente al terminar de hablar</span>
        </label>
      )}
    </div>
  );
}
