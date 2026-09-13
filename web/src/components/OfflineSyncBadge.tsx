import { useOfflineStore } from "../store/offlineStore";
import "./OfflineSyncBadge.css";

/**
 * Aviso de §8.4, con el texto exacto que exige el plan: el riesgo tiene que
 * ser consciente. El usuario elige seguir trabajando sabiendo que puede
 * perder algo, no descubriendolo despues.
 */
export function OfflineSyncBadge() {
  const state = useOfflineStore((s) => s.state);
  const queueLength = useOfflineStore((s) => s.queueLength);

  if (state === "ONLINE" || state === "RECONCILED") return null;

  if (state === "SYNCING") {
    return (
      <div className="offline-badge offline-badge--syncing">
        ⏳ Sincronizando {queueLength > 0 ? `${queueLength} cambio(s) pendiente(s)` : "…"}
      </div>
    );
  }

  return (
    <div className="offline-badge offline-badge--offline">
      ⚠ Sin conexión — tus cambios se aplicarán al reconectar y podrían ser descartados si otro
      colaborador edita lo mismo
      {queueLength > 0 && <span className="offline-badge__count"> ({queueLength} en la cola)</span>}
    </div>
  );
}
