import { useReviewStore } from "./reviewStore";
import "./ReviewCanvasBanner.css";

/**
 * Banner flotante sobre el lienzo que indica la revisión arquitectónica activa (CU22)
 * y ofrece un control rápido para ocultar o quitar las marcas de los nodos.
 */
export function ReviewCanvasBanner() {
  const activeReview = useReviewStore((s) => s.activeReview);
  const visibleOnCanvas = useReviewStore((s) => s.visibleOnCanvas);
  const clearReview = useReviewStore((s) => s.clearReview);

  if (!activeReview || !visibleOnCanvas) return null;

  const totalObs =
    (activeReview.totalErrors ?? 0) +
    (activeReview.totalWarnings ?? 0) +
    (activeReview.totalSuggestions ?? 0);

  if (totalObs === 0) return null;

  return (
    <div className="review-canvas-banner" role="status" aria-live="polite">
      <div className="review-canvas-banner__info">
        <span className="review-canvas-banner__icon" aria-hidden="true">📋</span>
        <span className="review-canvas-banner__title">
          Revisión v{activeReview.diagramVersion}
        </span>
        <span className="review-canvas-banner__badge">
          {totalObs} {totalObs === 1 ? "observación" : "observaciones"}
        </span>
      </div>
      <div className="review-canvas-banner__actions">
        <button
          type="button"
          className="review-canvas-banner__btn"
          onClick={() => clearReview()}
          title="Ocultar las insignias de advertencia y sugerencia del lienzo"
          aria-label="Ocultar marcas del lienzo"
        >
          Ocultar marcas ✕
        </button>
      </div>
    </div>
  );
}
