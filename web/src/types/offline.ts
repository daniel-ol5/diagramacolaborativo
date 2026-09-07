import type { CanonicalModel } from "./canonical";

/**
 * Espejo de backend/.../diagram/dto/{OfflineOperation,OfflineSyncRequest,
 * ReconciliationReport,OfflineSyncBroadcast}.java (§9.2, D-11).
 */

/** Una operacion encolada en IndexedDB mientras el cliente estaba OFFLINE. */
export interface OfflineOperation {
  op: string;
  targetId: string | null;
  /** Obligatorio a proposito (al reves que en el mensaje STOMP): es lo unico que hace seguro reenviar el lote. */
  clientMutationId: string;
  payload: Record<string, unknown>;
  /** No autoritativo — solo preserva el orden relativo original; el timestamp oficial lo pone el servidor. */
  clientTimestamp: number;
}

/** Cuerpo de POST /api/v1/diagrams/{id}/sync-offline. */
export interface OfflineSyncRequest {
  baseSequenceNum: number | null;
  operations: OfflineOperation[];
}

/** Respuesta del rebase — la muestra el modal de reconciliacion. */
export interface ReconciliationReport {
  appliedCount: number;
  discardedCount: number;
  discardedDetails: string[];
  snapshot: CanonicalModel;
}

/** Lo que recibe la sala (un solo evento, no N mutaciones) cuando otro cliente reconcilia. */
export interface OfflineSyncBroadcast {
  event: "OFFLINE_SYNC";
  userId: string;
  sequenceNum: number;
  appliedCount: number;
  serverTimestamp: string;
  snapshot: CanonicalModel;
}
