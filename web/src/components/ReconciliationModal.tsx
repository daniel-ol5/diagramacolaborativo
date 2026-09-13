import { Modal } from "./Modal";
import { useOfflineStore } from "../store/offlineStore";
import "./ReconciliationModal.css";

/**
 * Modal de reconciliacion (§9.2): muestra `discardedDetails` completo, no
 * solo los numeros. Un "2 descartadas" sin decir cuales deja al usuario sin
 * saber que perdio — y el servidor ya hizo el trabajo de resolver esos
 * textos contra la bitacora para que digan "la clase 'Cliente' fue
 * eliminada por otro colaborador" en vez de un UUID (D-23).
 */
export function ReconciliationModal() {
  const report = useOfflineStore((s) => s.reconciliationReport);
  const setReport = useOfflineStore((s) => s.setReconciliationReport);
  const setState = useOfflineStore((s) => s.setState);

  if (!report) return null;

  function close() {
    setReport(null);
    setState("ONLINE");
  }

  return (
    <Modal title="Sincronización completa" onClose={close}>
      <p className="reconciliation-modal__summary">
        <span className="reconciliation-modal__applied">{report.appliedCount} aplicado(s)</span>
        {" · "}
        <span className="reconciliation-modal__discarded">{report.discardedCount} descartado(s)</span>
      </p>

      {report.discardedCount > 0 && (
        <ul className="reconciliation-modal__details">
          {report.discardedDetails.map((detail, i) => (
            <li key={i}>{detail}</li>
          ))}
        </ul>
      )}

      <div className="modal__actions">
        <button type="button" className="modal__primary" onClick={close}>
          Entendido
        </button>
      </div>
    </Modal>
  );
}
