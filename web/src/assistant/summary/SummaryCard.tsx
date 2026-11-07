import type { ChatSummaryCardMeta } from "./types";
import "./SummaryCard.css";

interface Props {
  meta?: ChatSummaryCardMeta;
  textFallback?: string;
}

/**
 * Tarjeta de resumen de conversación (CU24).
 * Presenta las decisiones acordadas y las preguntas abiertas/pendientes de forma
 * estructurada, accesible y legible.
 */
export function SummaryCard({ meta, textFallback }: Props) {
  const decisions = meta?.decisions ?? [];
  const openQuestions = meta?.openQuestions ?? [];
  const fromSeq = meta?.fromSeq;
  const toSeq = meta?.toSeq;
  const total = meta?.totalMessages;

  const hasStructuredData =
    (fromSeq != null && toSeq != null) ||
    decisions.length > 0 ||
    openQuestions.length > 0;

  if (!hasStructuredData) {
    return (
      <div className="summary-card summary-card--fallback">
        <p className="summary-card__fallback-text">{textFallback}</p>
      </div>
    );
  }

  return (
    <article className="summary-card" aria-label="Resumen de la conversación">
      <header className="summary-card__header">
        <div className="summary-card__title-row">
          <span className="summary-card__icon" aria-hidden="true">📝</span>
          <span className="summary-card__title">Resumen de la conversación</span>
        </div>
        {fromSeq != null && toSeq != null && (
          <span className="summary-card__range" title={`Abarca los mensajes del ${fromSeq} al ${toSeq}`}>
            Mensajes {fromSeq}–{toSeq}
            {total != null && ` (${total})`}
          </span>
        )}
      </header>

      <section className="summary-card__section summary-card__section--decisions" aria-labelledby="summary-decisions-title">
        <h4 id="summary-decisions-title" className="summary-card__section-title">
          <span className="summary-card__bullet-icon" aria-hidden="true">✓</span>
          Decisiones y acuerdos
        </h4>
        {decisions.length > 0 ? (
          <ul className="summary-card__list">
            {decisions.map((d, i) => (
              <li key={i} className="summary-card__item summary-card__item--decision">
                {d}
              </li>
            ))}
          </ul>
        ) : (
          <p className="summary-card__empty-hint">Sin decisiones registradas aún.</p>
        )}
      </section>

      <section className="summary-card__section summary-card__section--questions" aria-labelledby="summary-questions-title">
        <h4 id="summary-questions-title" className="summary-card__section-title">
          <span className="summary-card__bullet-icon" aria-hidden="true">?</span>
          Preguntas abiertas / pendientes
        </h4>
        {openQuestions.length > 0 ? (
          <ul className="summary-card__list">
            {openQuestions.map((q, i) => (
              <li key={i} className="summary-card__item summary-card__item--question">
                {q}
              </li>
            ))}
          </ul>
        ) : (
          <p className="summary-card__empty-hint">No hay dudas pendientes por resolver.</p>
        )}
      </section>
    </article>
  );
}

