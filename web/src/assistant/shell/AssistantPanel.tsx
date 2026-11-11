import type { ReactNode } from "react";
import type { AssistantProvider } from "../settings/types";
import "./AssistantPanel.css";

interface Props {
  onClose: () => void;
  onOpenSettings?: () => void;
  onReviewDiagram?: () => void;
  isReviewing?: boolean;
  onSummarizeChat?: () => void;
  isSummarizing?: boolean;
  onImportPhoto?: () => void;
  provider?: AssistantProvider;
  /** El contenido de la pestaña activa (hoy, solo el chat). */
  children: ReactNode;
}

/**
 * Panel lateral del asistente, a la derecha del lienzo (no flota encima:
 * es hermano de .app__canvas dentro de .app__body, asi el lienzo se achica
 * y React Flow se reajusta solo).
 */
export function AssistantPanel({
  onClose,
  onOpenSettings,
  onReviewDiagram,
  isReviewing,
  onSummarizeChat,
  isSummarizing,
  onImportPhoto,
  provider,
  children,
}: Props) {
  return (
    <aside id="assistant-panel" className="assistant-panel" aria-labelledby="assistant-panel-title">
      <header className="assistant-panel__header">
        <h2 id="assistant-panel-title" className="assistant-panel__title">
          Chat IA
          <span
            className={`assistant-panel__provider-badge ${
              provider === "LOCAL"
                ? "assistant-panel__provider-badge--local"
                : "assistant-panel__provider-badge--cloud"
            }`}
            title={
              provider === "LOCAL"
                ? "Procesamiento con modelo local (Ollama)"
                : "Procesamiento en la nube (OpenAI / Gemini)"
            }
          >
            {provider === "LOCAL" ? "Local" : "Nube"}
          </span>
        </h2>
        <div className="assistant-panel__header-actions">
          {onImportPhoto && (
            <button
              type="button"
              className="assistant-panel__action-btn"
              onClick={onImportPhoto}
              title="Digitalizar e importar diagrama desde foto con IA multimodal (OpenAI)"
              aria-label="Importar diagrama desde foto"
            >
              Foto
            </button>
          )}
          {onSummarizeChat && (
            <button
              type="button"
              className="assistant-panel__action-btn"
              onClick={onSummarizeChat}
              disabled={isSummarizing}
              title="Condensar la conversación en decisiones y dudas pendientes (CU24)"
              aria-label="Resumir conversación"
            >
              {isSummarizing ? "Resumiendo…" : "Resumir"}
            </button>
          )}
          {onReviewDiagram && (
            <button
              type="button"
              className="assistant-panel__action-btn"
              onClick={onReviewDiagram}
              disabled={isReviewing}
              title="Pedirle a la IA una revisión arquitectónica del diagrama (CU22)"
              aria-label="Revisar diagrama con IA"
            >
              {isReviewing ? "Revisando…" : "Revisar"}
            </button>
          )}
          {onOpenSettings && (
            <button
              type="button"
              className="assistant-panel__icon-btn"
              onClick={onOpenSettings}
              title="Configuración de la IA del proyecto (CU21)"
              aria-label="Configuración de la IA"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
              </svg>
            </button>
          )}
          <button type="button" className="assistant-panel__close" onClick={onClose} aria-label="Cerrar el chat IA">
            ✕
          </button>
        </div>
      </header>
      <div className="assistant-panel__body">{children}</div>
    </aside>
  );
}
