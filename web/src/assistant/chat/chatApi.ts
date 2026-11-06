import { HTTP_BASE } from "../../services/config";
import { esChatMessage, type ChatHistoryPage } from "./types";

/** Error del historial del chat, con el status HTTP y el code del servidor si lo trae. */
export class ChatApiError extends Error {
  status: number;
  code: string | null;
  constructor(status: number, code: string | null, message: string) {
    super(message);
    this.name = "ChatApiError";
    this.status = status;
    this.code = code;
  }
}

export interface ChatHistoryQuery {
  beforeSeq?: number;
  afterSeq?: number;
  /** 50 por defecto en el servidor, maximo 100. */
  limit?: number;
}

/**
 * GET /api/v1/projects/{id}/chat. Siempre ascendente por seq.
 * - Sin cursores: los ultimos `limit`.
 * - Con afterSeq: los PRIMEROS posteriores; hasMore=true = hay mas nuevos.
 * Cualquier 403 es "no eres miembro" (puede venir sin code).
 */
export async function fetchChatHistory(
  projectId: string,
  token: string,
  query: ChatHistoryQuery = {},
): Promise<ChatHistoryPage> {
  const params = new URLSearchParams();
  if (query.beforeSeq != null) params.set("beforeSeq", String(query.beforeSeq));
  if (query.afterSeq != null) params.set("afterSeq", String(query.afterSeq));
  if (query.limit != null) params.set("limit", String(query.limit));
  const qs = params.toString();

  const res = await fetch(`${HTTP_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/chat${qs ? `?${qs}` : ""}`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  if (!res.ok) {
    let code: string | null = null;
    let message = `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { code?: unknown; message?: unknown };
      if (typeof body.code === "string") code = body.code;
      if (typeof body.message === "string" && body.message) message = body.message;
    } catch {
      // cuerpo vacio o no JSON: queda el status
    }
    if (res.status === 403) throw new ChatApiError(403, code ?? "NOT_PROJECT_MEMBER", "No eres miembro de este proyecto.");
    throw new ChatApiError(res.status, code, message);
  }

  const body = (await res.json()) as { messages?: unknown; hasMore?: unknown };
  const crudos = Array.isArray(body.messages) ? body.messages : [];
  const messages = crudos.filter((m) => {
    const ok = esChatMessage(m);
    if (!ok) console.warn("[chat] mensaje del historial descartado por formato:", m);
    return ok;
  });
  return { messages, hasMore: body.hasMore === true };
}
