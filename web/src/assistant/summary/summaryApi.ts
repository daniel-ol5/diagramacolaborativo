import { HTTP_BASE } from "../../services/config";
import type { ChatSummaryResponse } from "./types";

export class SummaryApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "SummaryApiError";
  }
}

/**
 * Dispara la generación manual de un resumen del chat del proyecto (CU24).
 * Solo permitido para OWNER o EDITOR (VIEWER recibe 403).
 */
export async function triggerChatSummary(
  projectId: string,
  token: string,
): Promise<ChatSummaryResponse | null> {
  const res = await fetch(`${HTTP_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/chat/summarize`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    },
  });

  if (res.status === 204) {
    return null;
  }

  if (!res.ok) {
    let msg = `Error al generar el resumen (${res.status})`;
    try {
      const body = (await res.json()) as { message?: string };
      if (body.message) msg = body.message;
    } catch {
      // Ignora cuerpo no JSON
    }
    throw new SummaryApiError(res.status, msg);
  }

  return (await res.json()) as ChatSummaryResponse;
}

/**
 * Consulta el último resumen registrado en el proyecto (CU24).
 * Disponible para cualquier miembro del proyecto.
 */
export async function fetchLatestSummary(
  projectId: string,
  token: string,
): Promise<ChatSummaryResponse | null> {
  const res = await fetch(`${HTTP_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/chat/summaries/latest`, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    },
  });

  if (res.status === 404 || res.status === 204) {
    return null;
  }

  if (!res.ok) {
    let msg = `Error al consultar el último resumen (${res.status})`;
    try {
      const body = (await res.json()) as { message?: string };
      if (body.message) msg = body.message;
    } catch {
      // Ignora cuerpo no JSON
    }
    throw new SummaryApiError(res.status, msg);
  }

  return (await res.json()) as ChatSummaryResponse;
}

