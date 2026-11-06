/**
 * Contrato del chat del proyecto (CU19). Los campos nulos el backend los
 * OMITE, por eso todo lo que puede faltar es opcional.
 */
export type ChatAuthorType = "USER" | "AI" | "SYSTEM";
export type ChatKind = "CHAT" | "ANSWER" | "REVIEW" | "SUMMARY" | "SYSTEM";

export interface ChatMessage {
  id: string;
  projectId: string;
  diagramId?: string;
  seq: number;
  authorType: ChatAuthorType;
  authorId?: string;
  authorName: string;
  authorColor?: string;
  addressedToId?: string;
  addressedToName?: string;
  replyToId?: string;
  clientMessageId?: string;
  kind: ChatKind;
  /** Siempre texto plano: se muestra tal cual, nunca como HTML ni Markdown. */
  text: string;
  /** ISO-8601 UTC. */
  createdAt: string;
  meta?: Record<string, unknown>;
}

/** Lo que llega por /topic/projects/{projectId}/chat. */
export type ChatTopicEvent =
  | { event: "CHAT_MESSAGE"; message: ChatMessage }
  | { event: "AI_TYPING"; forUserId: string; forUserName: string; active: boolean };

export interface ChatHistoryPage {
  messages: ChatMessage[];
  hasMore: boolean;
}

/**
 * Valida lo minimo para poder ordenar y mostrar un mensaje. Lo que no pasa
 * se descarta (con un aviso en consola), no rompe la lista.
 */
export function esChatMessage(x: unknown): x is ChatMessage {
  if (!x || typeof x !== "object") return false;
  const m = x as Record<string, unknown>;
  return (
    typeof m.id === "string" &&
    typeof m.seq === "number" &&
    Number.isFinite(m.seq) &&
    typeof m.text === "string" &&
    typeof m.authorName === "string" &&
    typeof m.createdAt === "string" &&
    (m.authorType === "USER" || m.authorType === "AI" || m.authorType === "SYSTEM")
  );
}
