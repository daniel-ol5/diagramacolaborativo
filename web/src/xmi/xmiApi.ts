import { HTTP_BASE } from "../services/config";
import type { DiagramSummary } from "../access/types";
import type { ValidationReport } from "../generator/types";

/** El `{status, error, message, path}` que arma Spring desde ResponseStatusException. */
async function mensajeDeError(res: Response, prefijo: string): Promise<Error> {
  const body = (await res.json().catch(() => ({}))) as { message?: string };
  return new Error(body.message ? `${prefijo}: ${body.message}` : `${prefijo} (HTTP ${res.status})`);
}

/**
 * GET /api/v1/diagrams/{id}/export-xmi (CU18).
 *
 * Igual que generar el backend, no se puede disparar con un `<a href>` ni
 * un `window.open()`: el endpoint exige `Authorization: Bearer` y ninguno
 * de los dos manda encabezados. Hay que pedirlo con fetch y disparar la
 * descarga desde el cliente con el binario ya en memoria.
 *
 * Lo puede pedir cualquier miembro, VIEWER incluido -- exportar es leer.
 */
export async function exportXmi(
  diagramId: string,
  token: string,
): Promise<{ blob: Blob; filename: string }> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/export-xmi`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw await mensajeDeError(res, "no se pudo exportar el XMI");

  // El nombre sale del Content-Disposition, que el servidor ya derivó del
  // nombre del diagrama (XmiController.nombreDeArchivo): inventarlo acá
  // daría dos nombres distintos para el mismo archivo según quién lo baje.
  const disposition = res.headers.get("Content-Disposition") ?? "";
  const nombreDeCabecera = disposition.match(/filename="([^"]+)"/)?.[1];
  return { blob: await res.blob(), filename: nombreDeCabecera ?? "diagrama.xmi" };
}

export type ImportXmiResult =
  | { ok: true; diagram: DiagramSummary }
  | { ok: false; report: ValidationReport };

/**
 * POST /api/v1/projects/{projectId}/import-xmi (CU18).
 *
 * Tres cosas que no salen de la firma y que el llamador tiene que saber:
 *
 * 1. **Crea un diagrama nuevo**, no sobrescribe el abierto. Por eso cuelga
 *    del proyecto y no del diagrama, y por eso devuelve un DiagramResponse:
 *    después de importar hay que ir al diagrama nuevo, no quedarse en el
 *    viejo mirando un lienzo que no cambió.
 * 2. **422 no es un error de red, es el informe de CU13.** Un XMI de otra
 *    herramienta puede traer cosas que nuestro metamodelo rechaza (herencia
 *    múltiple, por ejemplo). Mirar `res.status` antes de tocar el cuerpo es
 *    obligatorio: tratar el 422 como fallo genérico tira el informe, que es
 *    justamente lo único que le dice a la persona qué arreglar.
 * 3. FormData a propósito y **sin** fijar Content-Type a mano -- el
 *    navegador arma el boundary de multipart solo.
 */
export async function importXmi(
  projectId: string,
  token: string,
  file: File,
): Promise<ImportXmiResult> {
  const form = new FormData();
  form.append("file", file);

  const res = await fetch(`${HTTP_BASE}/api/v1/projects/${projectId}/import-xmi`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });

  if (res.status === 422) {
    return { ok: false, report: await res.json() };
  }
  if (!res.ok) throw await mensajeDeError(res, "no se pudo importar el XMI");
  return { ok: true, diagram: await res.json() };
}
