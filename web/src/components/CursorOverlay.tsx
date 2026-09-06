import { useViewport } from "reactflow";
import { useCollaborationStore } from "../store/collaborationStore";
import "./CursorOverlay.css";

/**
 * Cursores ajenos. Se renderiza como hijo de <ReactFlow> para poder usar
 * `useViewport()` (el mismo pan/zoom que aplica el lienzo) y convertir a
 * mano las coordenadas de flujo de cada cursor a pixeles de pantalla — no
 * hay una capa de overlay que React Flow ofrezca gratis para esto.
 */
export function CursorOverlay() {
  const cursors = useCollaborationStore((s) => s.cursors);
  const viewport = useViewport();

  return (
    <div className="cursor-overlay">
      {Object.values(cursors).map((cursor) => (
        <div
          key={cursor.userId}
          className="cursor-overlay__cursor"
          style={{
            left: viewport.x + cursor.x * viewport.zoom,
            top: viewport.y + cursor.y * viewport.zoom,
          }}
        >
          <span className="cursor-overlay__dot" style={{ background: cursor.color }} />
          <span className="cursor-overlay__label" style={{ background: cursor.color }}>
            {cursor.userName}
          </span>
        </div>
      ))}
    </div>
  );
}
