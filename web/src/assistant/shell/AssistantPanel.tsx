import type { ReactNode } from "react";
import "./AssistantPanel.css";

interface Props {
  onClose: () => void;
  /** El contenido de la pestaña activa (hoy, solo el chat). */
  children: ReactNode;
}

/**
 * Panel lateral del asistente, a la derecha del lienzo (no flota encima:
 * es hermano de .app__canvas dentro de .app__body, asi el lienzo se achica
 * y React Flow se reajusta solo).
 *
 * Por ahora solo el "Chat IA". Preparado para sumar pestañas (resumenes,
 * revision) en la cabecera sin cambiar el marco: el contenido llega como
 * children.
 */
export function AssistantPanel({ onClose, children }: Props) {
  return (
    <aside id="assistant-panel" className="assistant-panel" aria-labelledby="assistant-panel-title">
      <header className="assistant-panel__header">
        <h2 id="assistant-panel-title" className="assistant-panel__title">
          Chat IA
        </h2>
        <button type="button" className="assistant-panel__close" onClick={onClose} aria-label="Cerrar el chat IA">
          ✕
        </button>
      </header>
      <div className="assistant-panel__body">{children}</div>
    </aside>
  );
}
