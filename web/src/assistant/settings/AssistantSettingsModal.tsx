import { useEffect, useState, type FormEvent } from "react";
import { Modal } from "../../components/Modal";
import { fetchAssistantSettings, updateAssistantSettings } from "./settingsApi";
import type {
  AssistantLevel,
  AssistantMode,
  AssistantProvider,
  AssistantSettingsResponse,
} from "./types";
import "./AssistantSettingsModal.css";

interface Props {
  projectId: string;
  token: string;
  onClose: () => void;
  onSaved?: (settings: AssistantSettingsResponse) => void;
}

export function AssistantSettingsModal({ projectId, token, onClose, onSaved }: Props) {
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const [mode, setMode] = useState<AssistantMode>("ON_MENTION");
  const [level, setLevel] = useState<AssistantLevel>("INTERMEDIATE");
  const [provider, setProvider] = useState<AssistantProvider>("CLOUD");
  const [localEndpoint, setLocalEndpoint] = useState<string>("http://localhost:11434/v1");
  const [summaryFrequency, setSummaryFrequency] = useState(30);
  const [canEdit, setCanEdit] = useState(false);

  useEffect(() => {
    let activo = true;
    setLoading(true);
    setError(null);

    fetchAssistantSettings(projectId, token)
      .then((data: AssistantSettingsResponse) => {
        if (!activo) return;
        setMode(data.mode);
        setLevel(data.level);
        setProvider(data.provider ?? "CLOUD");
        setLocalEndpoint(data.localEndpoint || "http://localhost:11434/v1");
        setSummaryFrequency(data.summaryFrequency ?? 30);
        setCanEdit(data.canEdit);
        setLoading(false);
      })
      .catch((err: Error) => {
        if (!activo) return;
        setError(err.message || "No se pudo cargar la configuración.");
        setLoading(false);
      });

    return () => {
      activo = false;
    };
  }, [projectId, token]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!canEdit || saving) return;

    setSaving(true);
    setError(null);
    setSuccess(false);

    try {
      const endpointEfectivo =
        provider === "LOCAL"
          ? (localEndpoint.trim() || "http://localhost:11434/v1")
          : null;

      const updated = await updateAssistantSettings(projectId, token, {
        mode,
        level,
        provider,
        localEndpoint: endpointEfectivo,
        summaryFrequency: Number(summaryFrequency) || 30,
      });
      setSuccess(true);
      onSaved?.(updated);
      setTimeout(() => {
        onClose();
      }, 1000);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Error al guardar los ajustes";
      setError(msg);
      setSaving(false);
    }
  }

  return (
    <Modal title="Configuración de la IA del proyecto" onClose={onClose}>
      {loading ? (
        <p className="assistant-settings__hint">Cargando configuración…</p>
      ) : (
        <form onSubmit={handleSubmit} className="assistant-settings__form">
          {!canEdit && (
            <div className="assistant-settings__notice" role="alert">
              ℹ Estás viendo los ajustes en <strong>modo lectura</strong>. Solo el propietario (OWNER) del proyecto puede modificarlos.
            </div>
          )}

          {error && (
            <div className="assistant-settings__error" role="alert">
              ⚠ {error}
            </div>
          )}

          {success && (
            <div className="assistant-settings__success" role="status">
              ✓ Configuración guardada correctamente.
            </div>
          )}

          {/* PROVEEDOR DE IA (NUBE vs LOCAL OLLAMA) */}
          <div className="assistant-settings__group">
            <label className="assistant-settings__label" htmlFor="asst-provider">
              Proveedor de IA
            </label>
            <select
              id="asst-provider"
              value={provider}
              disabled={!canEdit || saving}
              onChange={(e) => setProvider(e.target.value as AssistantProvider)}
              className="assistant-settings__select"
            >
              <option value="CLOUD">En la nube (OpenAI / Gemini)</option>
              <option value="LOCAL">Local (Ollama en red / localhost)</option>
            </select>
            <span className="assistant-settings__help">
              Elige si las respuestas del chat, resumen y revisión se procesan en la nube o en tu servidor local Ollama.
            </span>
          </div>

          {/* ENDPOINT LOCAL (SOLO SI ES LOCAL) */}
          {provider === "LOCAL" && (
            <div className="assistant-settings__group">
              <label className="assistant-settings__label" htmlFor="asst-local-endpoint">
                URL del endpoint Ollama
              </label>
              <input
                id="asst-local-endpoint"
                type="text"
                value={localEndpoint}
                placeholder="http://localhost:11434/v1"
                disabled={!canEdit || saving}
                onChange={(e) => setLocalEndpoint(e.target.value)}
                className="assistant-settings__input"
              />
              <span className="assistant-settings__help">
                Usa <code>http://localhost:11434/v1</code> (local), <code>http://host.docker.internal:11434/v1</code> (Docker) o la IP privada de la laptop (ej. <code>http://192.168.1.50:11434/v1</code>).
              </span>
            </div>
          )}

          {/* MODO DE INTERVENCIÓN */}
          <div className="assistant-settings__group">
            <label className="assistant-settings__label" htmlFor="asst-mode">
              Modo de intervención
            </label>
            <select
              id="asst-mode"
              value={mode}
              disabled={!canEdit || saving}
              onChange={(e) => setMode(e.target.value as AssistantMode)}
              className="assistant-settings__select"
            >
              <option value="ON_MENTION">Solo con @IA o respuestas (Recomendado)</option>
              <option value="ALWAYS">Siempre activa (responde a cada mensaje)</option>
              <option value="MENTOR_PROACTIVE">Mentor proactivo (sugiere mejoras de diseño)</option>
              <option value="OFF">Desactivada / Apagada</option>
            </select>
            <span className="assistant-settings__help">
              Define cuándo la IA interviene automáticamente en el chat grupal.
            </span>
          </div>

          {/* NIVEL PEDAGÓGICO */}
          <div className="assistant-settings__group">
            <label className="assistant-settings__label" htmlFor="asst-level">
              Nivel pedagógico del Mentor
            </label>
            <select
              id="asst-level"
              value={level}
              disabled={!canEdit || saving}
              onChange={(e) => setLevel(e.target.value as AssistantLevel)}
              className="assistant-settings__select"
            >
              <option value="BEGINNER">Principiante (palabras simples y ejemplos concretos)</option>
              <option value="INTERMEDIATE">Intermedio (balance conceptual y técnico)</option>
              <option value="ADVANCED">Avanzado (técnico, formal y directo al grano)</option>
            </select>
            <span className="assistant-settings__help">
              Ajusta la profundidad y estilo de las explicaciones conceptuales.
            </span>
          </div>

          {/* FRECUENCIA DE RESUMEN */}
          <div className="assistant-settings__group">
            <label className="assistant-settings__label" htmlFor="asst-frequency">
              Frecuencia de resumen automático (mensajes)
            </label>
            <input
              id="asst-frequency"
              type="number"
              min={10}
              max={100}
              value={summaryFrequency}
              disabled={!canEdit || saving}
              onChange={(e) => setSummaryFrequency(Number(e.target.value))}
              className="assistant-settings__input"
            />
            <span className="assistant-settings__help">
              Cada cuántos mensajes se condensan decisiones y preguntas en el chat.
            </span>
          </div>

          <div className="modal__actions">
            <button type="button" onClick={onClose} disabled={saving}>
              {canEdit ? "Cancelar" : "Cerrar"}
            </button>
            {canEdit && (
              <button type="submit" className="modal__primary" disabled={saving}>
                {saving ? "Guardando…" : "Guardar cambios"}
              </button>
            )}
          </div>
        </form>
      )}
    </Modal>
  );
}

