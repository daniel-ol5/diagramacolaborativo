import "./TypingIndicator.css";

interface Props {
  /** Nombres de las personas para las que la IA esta respondiendo ahora. */
  forUserNames: string[];
}

/**
 * "La IA está escribiendo para {forUserName}…" mientras haya un AI_TYPING
 * active=true (se apaga con active=false o, como seguro, a los 45 s; eso lo
 * maneja el store). role="status": lo anuncia sin interrumpir.
 */
export function TypingIndicator({ forUserNames }: Props) {
  const nombres = forUserNames.filter((n) => n.trim() !== "");
  const activo = forUserNames.length > 0;
  return (
    <div className="typing-indicator" role="status" aria-live="polite">
      {activo && (
        <p className="typing-indicator__text">
          <span className="typing-indicator__dots" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          {nombres.length > 0 ? `La IA está escribiendo para ${nombres.join(", ")}…` : "La IA está escribiendo…"}
        </p>
      )}
    </div>
  );
}
