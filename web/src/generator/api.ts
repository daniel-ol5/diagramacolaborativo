import { HTTP_BASE } from "../services/config";
import type { ValidationReport } from "./types";

/** GET /api/v1/diagrams/{id}/validate (CU13). Lo puede pedir cualquier miembro, VIEWER incluido. */
export async function validateDiagram(diagramId: string, token: string): Promise<ValidationReport> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/validate`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`no se pudo validar (HTTP ${res.status})${text ? `: ${text.slice(0, 200)}` : ""}`);
  }
  return res.json();
}

export type GenerateBackendResult =
  | { ok: true; blob: Blob; filename: string }
  | { ok: false; report: ValidationReport };

/**
 * POST /api/v1/diagrams/{id}/generate-backend (CU14).
 *
 * Dos trampas que no salen de la firma del endpoint:
 *
 * 1. Requiere `Authorization: Bearer`, así que no se puede disparar con un
 *    `<a href>` ni un `window.open()` -- ninguno de los dos manda
 *    encabezados. Hay que pedirlo con fetch y disparar la descarga desde
 *    el cliente una vez que el binario ya está en memoria.
 * 2. El mismo endpoint devuelve un binario (200) o el informe de
 *    validación (422) según si el diagrama tiene errores bloqueantes.
 *    Mirar `res.status` ANTES de decidir qué hacer con el cuerpo es
 *    obligatorio: tratarlo siempre como archivo baja un .zip cuyo
 *    contenido es el texto del error, y el síntoma que ve el usuario
 *    ("el zip está corrupto") no se parece en nada a la causa.
 */
export async function generateBackend(
  diagramId: string,
  token: string,
  basePackage: string,
): Promise<GenerateBackendResult> {
  const res = await fetch(
    `${HTTP_BASE}/api/v1/diagrams/${diagramId}/generate-backend?basePackage=${encodeURIComponent(basePackage)}`,
    { method: "POST", headers: { Authorization: `Bearer ${token}` } },
  );

  if (res.status === 422) {
    return { ok: false, report: await res.json() };
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`no se pudo generar el backend (HTTP ${res.status})${text ? `: ${text.slice(0, 200)}` : ""}`);
  }

  const disposition = res.headers.get("Content-Disposition") ?? "";
  const nombreDeCabecera = disposition.match(/filename="([^"]+)"/)?.[1];
  const blob = await res.blob();
  return { ok: true, blob, filename: nombreDeCabecera ?? "backend-generado.zip" };
}
