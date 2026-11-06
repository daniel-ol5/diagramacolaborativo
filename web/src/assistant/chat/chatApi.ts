import { HTTP_BASE } from "../../services/config";
import { esChatMessage, type ChatAuthorType, type ChatHistoryPage, type ChatKind } from "./types";

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
  /** Busqueda por texto, sin distinguir mayusculas: 2 a 100 caracteres (si no, 400). */
  q?: string;
  authorType?: ChatAuthorType;
  kind?: ChatKind;
}

/** GET/PUT /chat/read. unreadCount NO cuenta los mensajes propios. */
export interface ChatReadState {
  lastReadSeq: number;
  unreadCount: number;
  lastSeq: number;
}

function urlDelChat(projectId: string, sufijo = ""): string {
  return `${HTTP_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/chat${sufijo}`;
}

/** Convierte una respuesta no-OK en ChatApiError. Cualquier 403 es "no eres miembro" (puede venir sin code). */
async function errorDeRespuesta(res: Response): Promise<ChatApiError> {
  let code: string | null = null;
  let message = `HTTP ${res.status}`;
  try {
    const body = (await res.json()) as { code?: unknown; message?: unknown };
    if (typeof body.code === "string") code = body.code;
    if (typeof body.message === "string" && body.message) message = body.message;
  } catch {
    // cuerpo vacio o no JSON: queda el status
  }
  if (res.status === 403) return new ChatApiError(403, code ?? "NOT_PROJECT_MEMBER", "No eres miembro de este proyecto.");
  return new ChatApiError(res.status, code, message);
}

/**
 * GET /api/v1/projects/{id}/chat. Siempre ascendente por seq.
 * - Sin cursores: los ultimos `limit`.
 * - Con afterSeq: los PRIMEROS posteriores; hasMore=true = hay mas nuevos.
 * - Con beforeSeq: los inmediatamente anteriores; hasMore=true = hay mas viejos.
 * - q, authorType y kind filtran y se pueden combinar con los cursores.
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
  if (query.q) params.set("q", query.q);
  if (query.authorType) params.set("authorType", query.authorType);
  if (query.kind) params.set("kind", query.kind);
  const qs = params.toString();

  const res = await fetch(urlDelChat(projectId, qs ? `?${qs}` : ""), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw await errorDeRespuesta(res);

  const body = (await res.json()) as { messages?: unknown; hasMore?: unknown };
  const crudos = Array.isArray(body.messages) ? body.messages : [];
  const messages = crudos.filter((m) => {
    const ok = esChatMessage(m);
    if (!ok) console.warn("[chat] mensaje del historial descartado por formato:", m);
    return ok;
  });
  return { messages, hasMore: body.hasMore === true };
}

function aReadState(body: unknown): ChatReadState {
  const b = (body ?? {}) as Record<string, unknown>;
  const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : 0);
  return { lastReadSeq: num(b.lastReadSeq), unreadCount: num(b.unreadCount), lastSeq: num(b.lastSeq) };
}

/** GET /api/v1/projects/{id}/chat/read. */
export async function fetchReadState(projectId: string, token: string): Promise<ChatReadState> {
  const res = await fetch(urlDelChat(projectId, "/read"), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw await errorDeRespuesta(res);
  return aReadState(await res.json());
}

/**
 * PUT /api/v1/projects/{id}/chat/read con {upToSeq}. Solo avanza, es
 * idempotente y el servidor lo recorta a lastSeq.
 */
export async function putReadState(projectId: string, token: string, upToSeq: number): Promise<ChatReadState> {
  const res = await fetch(urlDelChat(projectId, "/read"), {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ upToSeq }),
  });
  if (!res.ok) throw await errorDeRespuesta(res);
  return aReadState(await res.json());
}
