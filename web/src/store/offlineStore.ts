import { create } from "zustand";
import type { ReconciliationReport } from "../types/offline";

/**
 * Maquina de estados de §9.1, con esos cuatro nombres textuales a proposito
 * (van a aparecer en la defensa). Vive en su propio store, no en
 * `collaborationStore`: no es un lock ni un cursor ajeno, es el estado de
 * MI PROPIA relacion con el servidor — mas parecido al `status` de
 * useCollaboration, pero con la semantica extra de "tengo una cola
 * pendiente" que ese status nunca necesito.
 */
export type ConnectionState = "ONLINE" | "OFFLINE" | "SYNCING" | "RECONCILED";

interface OfflineState {
  state: ConnectionState;
  setState: (state: ConnectionState) => void;

  /** Cuantas operaciones hay en la cola de IndexedDB ahora mismo (para el badge). */
  queueLength: number;
  setQueueLength: (n: number) => void;

  /** El ultimo reporte de reconciliacion, para el modal. null = no hay nada que mostrar. */
  reconciliationReport: ReconciliationReport | null;
  setReconciliationReport: (report: ReconciliationReport | null) => void;
}

export const useOfflineStore = create<OfflineState>((set) => ({
  state: "ONLINE",
  setState: (state) => set({ state }),

  queueLength: 0,
  setQueueLength: (queueLength) => set({ queueLength }),

  reconciliationReport: null,
  setReconciliationReport: (reconciliationReport) => set({ reconciliationReport }),
}));
