import "./AssistantToggleButton.css";

interface Props {
  open: boolean;
  onToggle: () => void;
}

/**
 * Boton "Chat IA" del lado derecho de la barra, junto a "● en vivo". Ya no
 * va dentro de .app__toolbar-group, asi que trae su propio estilo (mismo
 * aspecto que los demas botones de la barra) y su propio estado activo.
 */
export function AssistantToggleButton({ open, onToggle }: Props) {
  return (
    <button
      type="button"
      className={`assistant-toggle${open ? " assistant-toggle--active" : ""}`}
      onClick={onToggle}
      aria-pressed={open}
      aria-controls="assistant-panel"
      title="Chat del proyecto con la IA"
    >
      <svg className="assistant-toggle__icon" width="14" height="14" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <path
          d="M2.5 3.5h11a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H7l-3 2.5v-2.5H2.5a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
      </svg>
      Chat IA
    </button>
  );
}
