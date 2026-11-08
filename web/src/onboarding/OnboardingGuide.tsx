import { useEffect, useState, useCallback } from "react";
import "./OnboardingGuide.css";

export interface GuideStep {
  selector: string;
  title: string;
  description: string;
}

interface Props {
  screenId: "projects" | "project" | "diagram";
  steps: GuideStep[];
  /** Permite abrir manualmente la guía desde el botón "Guía" del encabezado. */
  manualOpen: boolean;
  onCloseManual: () => void;
}

interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

const STORAGE_PREFIX = "modelcollab.onboarding.v1.";

export function OnboardingGuide({ screenId, steps, manualOpen, onCloseManual }: Props) {
  const storageKey = `${STORAGE_PREFIX}${screenId}`;
  const [autoOpen, setAutoOpen] = useState<boolean>(() => {
    try {
      return localStorage.getItem(storageKey) !== "done";
    } catch {
      return false;
    }
  });
  const isOpen = manualOpen || autoOpen;

  const [stepIndex, setStepIndex] = useState(0);
  const [targetRect, setTargetRect] = useState<Rect | null>(null);

  // Filtrar pasos cuyos elementos estén visibles en el DOM actual
  const [activeSteps, setActiveSteps] = useState<GuideStep[]>(steps);

  const refreshActiveSteps = useCallback(() => {
    const disponibles = steps.filter((s) => {
      const el = document.querySelector(s.selector);
      if (!el) return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    });
    setActiveSteps(disponibles.length > 0 ? disponibles : steps);
  }, [steps]);

  useEffect(() => {
    if (!isOpen) return;
    const timer = window.setTimeout(() => {
      refreshActiveSteps();
      setStepIndex(0);
    }, 120);
    return () => window.clearTimeout(timer);
  }, [isOpen, refreshActiveSteps]);

  const currentStep = activeSteps[stepIndex] ?? activeSteps[0];

  const updateRect = useCallback(() => {
    if (!isOpen || !currentStep) return;
    const el = document.querySelector(currentStep.selector);
    if (!el) {
      setTargetRect(null);
      return;
    }
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) {
      setTargetRect(null);
      return;
    }
    setTargetRect({
      top: r.top,
      left: r.left,
      width: r.width,
      height: r.height,
    });
  }, [isOpen, currentStep]);

  useEffect(() => {
    if (!isOpen) return;
    updateRect();
    window.addEventListener("resize", updateRect);
    window.addEventListener("scroll", updateRect, true);
    return () => {
      window.removeEventListener("resize", updateRect);
      window.removeEventListener("scroll", updateRect, true);
    };
  }, [isOpen, stepIndex, activeSteps, updateRect]);

  function finishGuide() {
    try {
      localStorage.setItem(storageKey, "done");
    } catch {
      // Ignorar si localStorage no está disponible
    }
    setAutoOpen(false);
    onCloseManual();
  }

  if (!isOpen || !currentStep) {
    return null;
  }

  const total = activeSteps.length;
  const isLast = stepIndex >= total - 1;

  // Calcular posición del globo del Agente Guía cerca del botón iluminado
  const PAD = 8;
  const cardWidth = 340;
  let cardTop = window.innerHeight / 2 - 110;
  let cardLeft = window.innerWidth / 2 - cardWidth / 2;

  if (targetRect) {
    const spaceBelow = window.innerHeight - (targetRect.top + targetRect.height);
    if (spaceBelow >= 210) {
      cardTop = targetRect.top + targetRect.height + 16;
    } else {
      cardTop = Math.max(16, targetRect.top - 205);
    }
    cardLeft = Math.min(
      Math.max(16, targetRect.left + targetRect.width / 2 - cardWidth / 2),
      window.innerWidth - cardWidth - 16,
    );
  }

  return (
    <div className="onboarding-guide" role="dialog" aria-modal="true" aria-label="Agente Guía de ModelCollab">
      {targetRect ? (
        <div
          className="onboarding-guide__spotlight"
          style={{
            top: targetRect.top - PAD,
            left: targetRect.left - PAD,
            width: targetRect.width + PAD * 2,
            height: targetRect.height + PAD * 2,
          }}
        />
      ) : (
        <div className="onboarding-guide__backdrop" onClick={finishGuide} />
      )}

      <div
        className="onboarding-guide__card"
        style={{
          top: Math.max(12, Math.min(cardTop, window.innerHeight - 220)),
          left: Math.max(12, cardLeft),
          width: cardWidth,
        }}
      >
        <div className="onboarding-guide__header">
          <span className="onboarding-guide__badge">
            Agente Guía · Paso {stepIndex + 1} de {total}
          </span>
          <button
            type="button"
            className="onboarding-guide__skip"
            onClick={finishGuide}
            aria-label="Cerrar guía"
          >
            Saltar
          </button>
        </div>

        <h3 className="onboarding-guide__title">{currentStep.title}</h3>
        <p className="onboarding-guide__desc">{currentStep.description}</p>

        <div className="onboarding-guide__footer">
          <div className="onboarding-guide__dots" aria-hidden="true">
            {activeSteps.map((_, idx) => (
              <span
                key={idx}
                className={`onboarding-guide__dot${idx === stepIndex ? " onboarding-guide__dot--active" : ""}`}
              />
            ))}
          </div>
          <div className="onboarding-guide__actions">
            {stepIndex > 0 && (
              <button
                type="button"
                className="onboarding-guide__btn onboarding-guide__btn--secondary"
                onClick={() => setStepIndex((i) => Math.max(0, i - 1))}
              >
                Anterior
              </button>
            )}
            <button
              type="button"
              className="onboarding-guide__btn onboarding-guide__btn--primary"
              onClick={() => {
                if (isLast) {
                  finishGuide();
                } else {
                  setStepIndex((i) => i + 1);
                }
              }}
            >
              {isLast ? "¡Entendido!" : "Siguiente"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

