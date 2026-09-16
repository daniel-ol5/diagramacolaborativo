import { HTTP_BASE } from "../services/config";

/** Espejo de backend/.../vision/dto/*.java (CU17, §14/RF-03). */
export interface VisionAttribute {
  name: string;
  type: string;
}

export interface VisionClass {
  name: string;
  attributes: VisionAttribute[];
}

export interface VisionRelationship {
  from: string;
  to: string;
  type: string;
  sourceMultiplicity: string;
  targetMultiplicity: string;
}

/** Todavia NO es un diagrama: nombres, no ids ni coordenadas (VisionDraft.java). Viaja en las dos direcciones, con las correcciones que la persona haya hecho. */
export interface VisionDraft {
  classes: VisionClass[];
  relationships: VisionRelationship[];
}

export interface VisionProposal {
  /** Si la lectura la hizo un modelo de verdad o el cliente simulado -- SIEMPRE hay que mostrarlo si es true, nunca ocultarlo (D-XX, RF-03). */
  simulated: boolean;
  message: string;
  draft: VisionDraft;
  /** Avisos de lo que el servidor ya toco por su cuenta (p.ej. renombro una clase duplicada). */
  notes: string[];
}

export interface VisionConfirmResult {
  applied: boolean;
  addedClasses: number;
  addedRelationships: number;
  mutationVersion: number | null;
}

export class VisionError extends Error {
  reason: string;
  constructor(reason: string, message: string) {
    super(message);
    this.name = "VisionError";
    this.reason = reason;
  }
}

async function leerError(res: Response): Promise<never> {
  const body = (await res.json().catch(() => ({}))) as { reason?: string; message?: string };
  throw new VisionError(body.reason ?? "UNKNOWN", body.message ?? `HTTP ${res.status}`);
}

/**
 * POST /api/v1/diagrams/{id}/vision-import (CU17, paso 1 de 2). Sube la foto
 * y recibe la propuesta -- este endpoint no sabe escribir, nada se aplico
 * todavia. FormData a proposito: NO se fija Content-Type a mano, el
 * navegador arma el boundary de multipart solo.
 */
export async function analyzeImage(diagramId: string, token: string, image: File): Promise<VisionProposal> {
  const form = new FormData();
  form.append("image", image);
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/vision-import`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  if (!res.ok) return leerError(res);
  return res.json();
}

/**
 * POST /api/v1/diagrams/{id}/vision-import/confirm (CU17, paso 2 de 2). El
 * borrador que se manda es el que la persona REVISO en el modal, con sus
 * correcciones -- puede diferir del que propuso el servidor, y se valida
 * entero de nuevo del otro lado (VisionDraft.java): que lo haya propuesto
 * el servidor un segundo antes no lo vuelve confiable.
 */
export async function confirmImport(
  diagramId: string,
  token: string,
  draft: VisionDraft,
  clientMutationId: string,
): Promise<VisionConfirmResult> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/vision-import/confirm`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ draft, clientMutationId }),
  });
  if (!res.ok) return leerError(res);
  return res.json();
}
