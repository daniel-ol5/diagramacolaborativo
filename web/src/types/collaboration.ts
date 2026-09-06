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

/** Ojo: Participante usa "nombre" (espaniol) — CursorBroadcastMessage/LockBroadcastMessage usan "userName". */
export interface Participante {
  userId: string;
  nombre: string;
  color: string;
}

/** Lo que se manda a /app/diagram/{id}/cursor. Sin clientMutationId ni op: no es una mutacion. */
export interface CursorMessage {
  x: number;
  y: number;
  selectedId: string | null;
}

/** Lo que llega por /topic/diagrams/{id} con la posicion de un cursor ajeno. */
export interface CursorBroadcastMessage {
  event: "USER_CURSOR";
  userId: string;
  userName: string;
  color: string;
  x: number;
  y: number;
  selectedId: string | null;
}

/** El roster completo: llega por /topic/diagrams/{id} en cada alta/baja y por /user/queue/presence al pedirlo. */
export interface PresenceMessage {
  event: "PRESENCE";
  participants: Participante[];
}

export type TopicEvent = LockBroadcastMessage | CursorBroadcastMessage | PresenceMessage;

export function esBroadcastDeMutacion(body: unknown): body is StompBroadcastMessage {
  return typeof body === "object" && body !== null && "op" in body;
}

/** Todo lo demas del topic (locks, cursores, presencia) comparte el campo "event". */
export function esEventoDeSala(body: unknown): body is TopicEvent {
  return typeof body === "object" && body !== null && "event" in body;
}
