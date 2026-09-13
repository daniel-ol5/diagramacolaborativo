import { HTTP_BASE } from "./config";
import type { OfflineOperation, ReconciliationReport } from "../types/offline";

/** POST /api/v1/diagrams/{id}/sync-offline (§9.2). El tope de 500 por lote lo exige el servidor. */
export async function syncOffline(
  diagramId: string,
  token: string,
  baseSequenceNum: number | null,
  operations: OfflineOperation[],
): Promise<ReconciliationReport> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/sync-offline`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ baseSequenceNum, operations }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`sync-offline falló (HTTP ${res.status})${text ? `: ${text.slice(0, 300)}` : ""}`);
  }
  return res.json();
}
