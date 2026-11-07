import type { ReviewCardMeta } from "./types";
import { useReviewStore } from "./reviewStore";
import "./ReviewCard.css";

interface Props {
  meta: ReviewCardMeta;
  currentMutationVersion?: number;
  textFallback?: string;
  onSelectElement?: (elementId: string) => void;
}

export function ReviewCard({
  meta,
  currentMutationVersion,
  textFallback,
  onSelectElement,
}: Props) {
  const activeReview = useReviewStore((s) => s.activeReview);
  const visibleOnCanvas = useReviewStore((s) => s.visibleOnCanvas);
  const setVisibleOnCanvas = useReviewStore((s) => s.setVisibleOnCanvas);
  const setActiveReview = useReviewStore((s) => s.setActiveReview);

  if (!meta || typeof meta.score !== "number") {
    return <p className="review-card__fallback">{textFallback}</p>;
  }

  const isCurrentCardActive =
    activeReview != null &&
    activeReview.diagramVersion === meta.diagramVersion &&
    visibleOnCanvas;

  const scoreClass =
    meta.score >= 80 ? "high" : meta.score >= 50 ? "mid" : "low";

  const isStale =
    currentMutationVersion != null &&
    meta.diagramVersion != null &&
    currentMutationVersion > meta.diagramVersion;

  const handleToggleCanvas = () => {
    if (isCurrentCardActive) {
      setVisibleOnCanvas(false);
    } else {
      setActiveReview({
        score: meta.score,
        totalErrors: meta.totalErrors ?? 0,
        totalWarnings: meta.totalWarnings ?? 0,
        totalSuggestions: meta.totalSuggestions ?? 0,
        diagramVersion: meta.diagramVersion ?? 1,
        mentorComment: meta.comment ?? "",
        issues: meta.issues ?? [],
      });
    }
  };

  return (
    <article className="review-card" aria-label="Tarjeta de revisión de diagrama">
      <header className="review-card__header">
        <div className="review-card__title-row">
          <span className="review-card__icon" aria-hidden="true">📋</span>
          <span className="review-card__title">Revisión de arquitectura</span>
          <span className="review-card__version">v{meta.diagramVersion}</span>
        </div>
        <div className={`review-card__score review-card__score--${scoreClass}`}>
          <span className="review-card__score-val">{meta.score}</span>
          <span className="review-card__score-total">/100</span>
        </div>
      </header>

      {isStale && (
        <div className="review-card__stale-warning" role="status">
          ⚠ Diagrama modificado después de esta revisión (v{meta.diagramVersion} vs v{currentMutationVersion})
        </div>
      )}

      <div className="review-card__pills">
        <span
          className={`review-card__pill ${
            meta.totalErrors > 0 ? "review-card__pill--error" : ""
          }`}
        >
          {meta.totalErrors} {meta.totalErrors === 1 ? "Error" : "Errores"}
        </span>
        <span
          className={`review-card__pill ${
            meta.totalWarnings > 0 ? "review-card__pill--warning" : ""
          }`}
        >
          {meta.totalWarnings}{" "}
          {meta.totalWarnings === 1 ? "Advertencia" : "Advertencias"}
        </span>
        <span className="review-card__pill review-card__pill--suggestion">
          {meta.totalSuggestions}{" "}
          {meta.totalSuggestions === 1 ? "Sugerencia" : "Sugerencias"}
        </span>
      </div>

      <div className="review-card__actions-row">
        <button
          type="button"
          className={`review-card__toggle-btn${isCurrentCardActive ? " review-card__toggle-btn--active" : ""}`}
          onClick={handleToggleCanvas}
          aria-pressed={isCurrentCardActive}
          title={isCurrentCardActive ? "Ocultar insignias en las clases del lienzo" : "Mostrar insignias en las clases del lienzo"}
        >
          {isCurrentCardActive ? "Ocultar marcas en lienzo" : "Ver marcas en el lienzo"}
        </button>
      </div>

      {meta.comment && (
        <blockquote className="review-card__comment">
          <div className="review-card__comment-label">Consejo del Mentor:</div>
          <p className="review-card__comment-text">{meta.comment}</p>
        </blockquote>
      )}

      {meta.issues && meta.issues.length > 0 && (
        <div className="review-card__issues">
          <div className="review-card__issues-title">Problemas detectados:</div>
          <ul className="review-card__issues-list">
            {meta.issues.slice(0, 6).map((issue, idx) => (
              <li
                key={`${issue.code}-${idx}`}
                className={`review-card__issue-item review-card__issue-item--${issue.severity.toLowerCase()}`}
                onClick={() => issue.elementId && onSelectElement?.(issue.elementId)}
                title={issue.elementId ? "Clic para enfocar elemento" : undefined}
                role={issue.elementId ? "button" : undefined}
                tabIndex={issue.elementId ? 0 : undefined}
              >
                <span className="review-card__issue-badge">
                  {issue.severity === "ERROR"
                    ? "Error"
                    : issue.severity === "WARNING"
                    ? "Aviso"
                    : "Sugerencia"}
                </span>
                <span className="review-card__issue-element">
                  {issue.elementName ? `${issue.elementName}: ` : ""}
                </span>
                <span className="review-card__issue-msg">{issue.message}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </article>
  );
}
