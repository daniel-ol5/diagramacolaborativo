import { HTTP_BASE } from "./config";
import type { CanonicalModel } from "../types/canonical";

/** El endpoint envuelve el canonico en metadatos del diagrama (id, name, mutationVersion propio); lo que importa vive en `currentState`. */
interface DiagramSnapshotResponse {
  id: string;
  name: string;
  mutationVersion: number;
  currentState: CanonicalModel;
}

/**
 * GET /api/v1/diagrams/{id}/snapshot. Se usa para resincronizar el canonico
 * cuando el servidor rechaza una mutacion optimista (LOCK_REQUIRED,
 * DUPLICATE_ID, TARGET_NOT_FOUND, etc.): NUNCA se deshace localmente,
 * porque entre la aplicacion optimista y el rechazo pudo haber llegado una
 * mutacion ajena, y deshacer la instantanea previa se la llevaria puesta
 * en silencio. Volver a pedir el estado real es lo unico que queda
 * correcto sin importar que paso en el medio.
 */
export async function fetchDiagramSnapshot(diagramId: string, token: string): Promise<CanonicalModel> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/snapshot`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`snapshot falló (HTTP ${res.status})${text ? `: ${text.slice(0, 300)}` : ""}`);
  }
  const body = (await res.json()) as DiagramSnapshotResponse;
  return body.currentState;
}
