import { Modal } from "../components/Modal";
import type { ValidationIssue, ValidationReport } from "./types";
import "./ValidationDialog.css";

interface Props {
  report: ValidationReport;
  /** true cuando este informe es el 422 de un intento de generar (bloqueado); false cuando vino de "Validar" a pedido. */
  blocked: boolean;
  onClose: () => void;
  /** Lleva la vista del lienzo a la clase/relacion culpable -- el diálogo pasa de informar a resolver. */
  onSelectElement: (elementId: string) => void;
}

/**
 * CU13/CU14: un solo componente para los dos caminos -- el GET /validate a
 * pedido y el 422 de un intento de generar bloqueado. Es el mismo informe
 * (ValidationReportDTO) en los dos casos, asi que mostrarlo distinto seria
 * inventar una diferencia que no existe.
 *
 * La distincion que SI tiene que verse es error vs advertencia: con
 * advertencias se genera igual, con errores no -- por eso van en secciones
 * separadas con su propio color, nunca mezcladas en una sola lista.
 */
export function ValidationDialog({ report, blocked, onClose, onSelectElement }: Props) {
  function handleClickIssue(issue: ValidationIssue) {
    onSelectElement(issue.elementId);
    onClose();
  }

  function renderLista(issues: ValidationIssue[]) {
    return (
      <div className="validation-dialog__list">
        {issues.map((issue, i) => (
          <button
            key={`${issue.code}-${issue.elementId}-${i}`}
            type="button"
            className="validation-dialog__issue"
            onClick={() => handleClickIssue(issue)}
            title="Ver en el lienzo"
          >
            <div className="validation-dialog__issue-element">
              {issue.elementName} · {issue.code}
            </div>
            <div className="validation-dialog__issue-message">{issue.message}</div>
          </button>
        ))}
      </div>
    );
  }

  return (
    <Modal title={blocked ? "No se generó el backend" : "Informe de validación"} onClose={onClose}>
      <div className="validation-dialog">
        {report.valid ? (
          <div className="validation-dialog__banner validation-dialog__banner--ok">
            ✓ Sin errores bloqueantes{report.warnings.length > 0 ? " — se puede generar igual" : ""}
          </div>
        ) : (
          <div className="validation-dialog__banner validation-dialog__banner--blocked">
            ✗ {report.errors.length} error{report.errors.length === 1 ? "" : "es"} bloqueante
            {report.errors.length === 1 ? "" : "s"} — no se puede generar
          </div>
        )}

        {report.errors.length > 0 && (
          <div className="validation-dialog__section validation-dialog__section--errors">
            <h3>Errores</h3>
            {renderLista(report.errors)}
          </div>
        )}

        {report.warnings.length > 0 && (
          <div className="validation-dialog__section validation-dialog__section--warnings">
            <h3>Advertencias</h3>
            {renderLista(report.warnings)}
          </div>
        )}

        {report.valid && report.warnings.length === 0 && <p>El diagrama no tiene nada que reportar.</p>}
      </div>
    </Modal>
  );
}
