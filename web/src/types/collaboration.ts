/**
 * Mensajes STOMP del Dia 8+, escritos directamente desde los records Java de
 * backend/src/main/java/com/modelcollab/collaboration/dto/ (contrato D-11,
 * congelado) — no adivinados.
 */

export interface StompMutationMessage {
  op: string;
  targetId: string | null;
  clientMutationId: string;
  payload: Record<string, unknown>;
}

/** Lo que llega por /topic/diagrams/{id} cuando alguien muta el diagrama. */
export interface StompBroadcastMessage {
  op: string;
  targetId: string | null;
  clientMutationId: string | null;
  payload: Record<string, unknown>;
  userId: string;
  serverTimestamp: string;
  sequenceNum: number;
}

/** Lo que llega por /user/queue/errors. */
export interface StompErrorMessage {
  status: number;
  code: string;
  destination: string;
  clientMutationId: string | null;
  message: string;
}

/** Lo que llega por /topic/diagrams/{id} cuando alguien pide/suelta un lock (Dia 9). */
export interface LockBroadcastMessage {
  event: "LOCK_ACQUIRED" | "LOCK_RELEASED";
  targetId: string;
  userId?: string;
  userName?: string;
  color?: string;
  ttl?: number;
}

/** Lo que llega por /user/queue/locks cuando el lock que pediste esta tomado (Dia 9). */
export interface LockDeniedMessage {
  event: "LOCK_DENIED";
  targetId: string;
  heldBy: string;
  expiresInMs: number;
}

export function esBroadcastDeMutacion(body: unknown): body is StompBroadcastMessage {
  return typeof body === "object" && body !== null && "op" in body;
}

export function esEventoDeLock(body: unknown): body is LockBroadcastMessage {
  return typeof body === "object" && body !== null && "event" in body;
}
