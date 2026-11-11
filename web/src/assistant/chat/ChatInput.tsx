import { useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { MAX_CHARS } from "./chatErrors";
import "./ChatInput.css";

interface Props {
  /** Sin conexion STOMP el campo se deshabilita (el chat NO usa la cola offline). */
  connected: boolean;
  /** Error de envio a mostrar en linea, bajo el campo. */
  error: string | null;
  /** true si se envio (entonces se vacia el campo). */
  onSend: (text: string) => boolean;
  /** Abrir modal de digitalización e importación de diagrama desde foto con OpenAI Vision. */
  onImportPhoto?: () => void;
}

/** A partir de aca se muestra el contador. */
const AVISO_DESDE = MAX_CHARS - 200;
const MENCION_IA = "@IA ";
/** "@IA" como palabra completa, sin distinguir mayusculas (igual que el backend). */
const YA_MENCIONA_IA = /(^|[^\p{L}\p{N}_])@ia(?![\p{L}\p{N}_])/iu;

/** Si lo que esta justo antes del cursor es una mencion a medio escribir ("@", "@i"), su inicio. */
function mencionEnCurso(value: string, caret: number): number | null {
  const antes = value.slice(0, caret);
  const m = /(^|\s)@([\p{L}\p{N}_]*)$/u.exec(antes);
  if (!m) return null;
  const parcial = m[2].toUpperCase();
  if (parcial === "IA" || !"IA".startsWith(parcial)) return null;
  return caret - m[2].length - 1;
}

/**
 * Campo de envio. Enter envia, Shift+Enter hace salto de linea. Maximo 2000
 * caracteres, con contador al acercarse. "Preguntar a la IA" inserta "@IA ",
 * y al escribir "@" se sugiere completarlo (Enter o Tab acepta, Esc cierra).
 */
export function ChatInput({ connected, error, onSend, onImportPhoto }: Props) {
  const [value, setValue] = useState("");
  const [caret, setCaret] = useState(0);
  const [sugerenciaCerrada, setSugerenciaCerrada] = useState(false);
  const ref = useRef<HTMLTextAreaElement | null>(null);
  const ids = useId();
  const idAviso = `${ids}-aviso`;
  const idContador = `${ids}-contador`;
  const idError = `${ids}-error`;
  const idSugerencia = `${ids}-sugerencia`;

  const inicioMencion = connected && !sugerenciaCerrada ? mencionEnCurso(value, caret) : null;
  const sugerir = inicioMencion != null;
  const texto = value.trim();
  const puedeEnviar = connected && texto.length > 0 && value.length <= MAX_CHARS;

  function ponerValor(nuevo: string, nuevoCaret: number) {
    const recortado = nuevo.slice(0, MAX_CHARS);
    const c = Math.min(nuevoCaret, recortado.length);
    setValue(recortado);
    setCaret(c);
    setSugerenciaCerrada(false);
    requestAnimationFrame(() => {
      const el = ref.current;
      if (!el) return;
      el.focus();
      el.setSelectionRange(c, c);
    });
  }

  function aceptarSugerencia() {
    if (inicioMencion == null) return;
    const nuevo = value.slice(0, inicioMencion) + MENCION_IA + value.slice(caret);
    ponerValor(nuevo, inicioMencion + MENCION_IA.length);
  }

  function insertarMencion() {
    if (YA_MENCIONA_IA.test(value)) {
      ref.current?.focus();
      return;
    }
    const el = ref.current;
    const pos = el ? el.selectionStart : value.length;
    const antes = value.slice(0, pos);
    const separador = antes.length > 0 && !/\s$/.test(antes) ? " " : "";
    const insertado = separador + MENCION_IA;
    ponerValor(antes + insertado + value.slice(pos), pos + insertado.length);
  }

  function enviar() {
    if (!puedeEnviar) return;
    if (onSend(value)) {
      setValue("");
      setCaret(0);
      setSugerenciaCerrada(false);
    }
  }

  function alEnviar(e: FormEvent) {
    e.preventDefault();
    enviar();
  }

  function alTeclear(e: KeyboardEvent<HTMLTextAreaElement>) {
    // Con un IME a medio componer, Enter confirma la composicion, no envia.
    if (e.nativeEvent.isComposing) return;
    if (sugerir && (e.key === "Enter" || e.key === "Tab") && !e.shiftKey) {
      e.preventDefault();
      aceptarSugerencia();
      return;
    }
    if (sugerir && e.key === "Escape") {
      e.preventDefault();
      setSugerenciaCerrada(true);
      return;
    }
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      enviar();
    }
  }

  const describedBy = [!connected && idAviso, value.length >= AVISO_DESDE && idContador, error && idError]
    .filter(Boolean)
    .join(" ");

  return (
    <form className="chat-input" onSubmit={alEnviar}>
      {!connected && (
        <p id={idAviso} className="chat-input__notice">
          Sin conexión con el servidor. El chat se reactivará al reconectar.
        </p>
      )}

      <div className="chat-input__field">
        <textarea
          ref={ref}
          className="chat-input__textarea"
          value={value}
          rows={2}
          maxLength={MAX_CHARS}
          disabled={!connected}
          placeholder={connected ? "Escribe un mensaje… (@IA para preguntarle a la IA)" : "Sin conexión"}
          aria-label="Mensaje para el chat del proyecto"
          aria-describedby={describedBy || undefined}
          aria-autocomplete="list"
          aria-controls={sugerir ? idSugerencia : undefined}
          aria-expanded={sugerir}
          onChange={(e) => {
            setValue(e.target.value);
            setCaret(e.target.selectionStart);
            setSugerenciaCerrada(false);
          }}
          onSelect={(e) => setCaret(e.currentTarget.selectionStart)}
          onKeyDown={alTeclear}
        />
        {sugerir && (
          <ul id={idSugerencia} className="chat-input__suggestions" role="listbox" aria-label="Sugerencias">
            <li
              className="chat-input__suggestion"
              role="option"
              aria-selected="true"
              // mousedown para no perder el foco del campo antes del click
              onMouseDown={(e) => {
                e.preventDefault();
                aceptarSugerencia();
              }}
            >
              <strong>@IA</strong> — preguntarle a la IA <span className="chat-input__kbd">Enter</span>
            </li>
          </ul>
        )}
      </div>

      <div className="chat-input__actions">
        {onImportPhoto && (
          <button
            type="button"
            className="chat-input__photo-btn"
            onClick={onImportPhoto}
            title="Digitalizar e importar diagrama desde foto con IA multimodal (OpenAI)"
            aria-label="Importar foto de diagrama"
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
              <circle cx="12" cy="13" r="4" />
            </svg>
            Foto
          </button>
        )}
        <button type="button" className="chat-input__ask" onClick={insertarMencion} disabled={!connected}>
          Preguntar a la IA
        </button>
        {value.length >= AVISO_DESDE && (
          <span
            id={idContador}
            className={`chat-input__counter${value.length >= MAX_CHARS ? " chat-input__counter--max" : ""}`}
          >
            {value.length} / {MAX_CHARS}
          </span>
        )}
        <button type="submit" className="chat-input__send" disabled={!puedeEnviar}>
          Enviar
        </button>
      </div>

      {error && (
        <p id={idError} className="chat-input__error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
