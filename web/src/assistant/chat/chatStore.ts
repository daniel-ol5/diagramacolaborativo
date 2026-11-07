import { create } from "zustand";
import type { ChatMessage } from "./types";

/**
 * Estado del chat del proyecto abierto (CU19). Vive fuera del panel: al
 * cerrarlo y volver a abrirlo los mensajes siguen ahi y solo se piden los
 * nuevos (afterSeq).
 */
export type ChatLoadStatus = "idle" | "loading" | "ready" | "error";

/** Un mensaje propio que todavia no tiene eco del servidor. */
export interface OutgoingMessage {
  clientMessageId: string;
  text: string;
  diagramId?: string;
  /** ISO-8601, hora local de cuando se escribio (solo para mostrar). */
  createdAt: string;
  status: "sending" | "failed";
  /** Por que no se envio (en espanol). */
  error?: string;
}

/** Si no llega AI_TYPING active=false en este tiempo, el indicador se apaga solo. */
export const AI_TYPING_SEGURO_MS = 45_000;
/** Si el eco de un mensaje propio no llega en este tiempo, queda "no se envió". */
export const CONFIRMACION_SEGURO_MS = 15_000;

interface ChatState {
  /** Proyecto al que pertenece todo lo de abajo; al cambiar de proyecto se vacia. */
  projectId: string | null;
  /** Confirmados por el servidor, ordenados por seq, sin duplicados por id ni por clientMessageId. */
  messages: ChatMessage[];
  /** Propios en camino o rechazados, en orden de envio. */
  outbox: OutgoingMessage[];
  loadStatus: ChatLoadStatus;
  /** Error del historial (REST). */
  error: string | null;
  /** Error de envio, visible en linea bajo el campo. */
  sendError: string | null;
  /** Si la conexion STOMP compartida esta arriba (sin ella no se escribe). */
  connected: boolean;
  /** AI_TYPING activos: forUserId -> forUserName. */
  aiTyping: Record<string, string>;

  ensureProject: (projectId: string) => void;
  mergeMessages: (incoming: ChatMessage[]) => void;
  setLoadStatus: (status: ChatLoadStatus) => void;
  setError: (error: string | null) => void;
  setSendError: (error: string | null) => void;
  setConnected: (connected: boolean) => void;

  addOutgoing: (message: OutgoingMessage) => void;
  markSending: (clientMessageId: string) => void;
  markFailed: (clientMessageId: string, error: string) => void;
  /** Todos los "enviando" pasan a "no se envió" (se cayo la conexion o se cerro el panel). */
  failAllSending: (error: string) => void;

  setAiTyping: (forUserId: string, forUserName: string, active: boolean) => void;
}

// Temporizadores fuera del estado: no son datos que se dibujen.
const typingTimers = new Map<string, ReturnType<typeof setTimeout>>();
const confirmTimers = new Map<string, ReturnType<typeof setTimeout>>();

function clearTimer(map: Map<string, ReturnType<typeof setTimeout>>, key: string) {
  const t = map.get(key);
  if (t !== undefined) {
    clearTimeout(t);
    map.delete(key);
  }
}

function clearAllTimers() {
  typingTimers.forEach((t) => clearTimeout(t));
  typingTimers.clear();
  confirmTimers.forEach((t) => clearTimeout(t));
  confirmTimers.clear();
}

/**
 * Fusiona por seq. Reglas:
 * - mismo id: gana el que llega (es la misma fila del servidor);
 * - mismo clientMessageId con otro id: el que llega reemplaza al viejo;
 * - huecos en seq se ignoran: solo se ordena.
 * Pura: no muta `existing`.
 */
export function mergeChatMessages(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  if (incoming.length === 0) return existing;
  const byId = new Map<string, ChatMessage>();
  const idByClientId = new Map<string, string>();

  const poner = (m: ChatMessage) => {
    if (m.clientMessageId) {
      const previo = idByClientId.get(m.clientMessageId);
      if (previo && previo !== m.id) byId.delete(previo);
      idByClientId.set(m.clientMessageId, m.id);
    }
    byId.set(m.id, m);
  };

  existing.forEach(poner);
  incoming.forEach(poner);

  return Array.from(byId.values()).sort((a, b) => a.seq - b.seq);
}

/** El seq mas alto conocido, o null si no hay mensajes. */
export function lastSeqOf(messages: ChatMessage[]): number | null {
  return messages.length === 0 ? null : messages[messages.length - 1].seq;
}

export const useChatStore = create<ChatState>()((set, get) => ({
  projectId: null,
  messages: [],
  outbox: [],
  loadStatus: "idle",
  error: null,
  sendError: null,
  connected: false,
  aiTyping: {},

  ensureProject: (projectId) => {
    if (get().projectId === projectId) return;
    clearAllTimers();
    set({
      projectId,
      messages: [],
      outbox: [],
      loadStatus: "idle",
      error: null,
      sendError: null,
      aiTyping: {},
    });
  },

  mergeMessages: (incoming) =>
    set((s) => {
      // El eco con el MISMO clientMessageId confirma al propio "enviando" (o
      // a uno marcado "no se envió" que en realidad si llego).
      const confirmados = new Set(incoming.map((m) => m.clientMessageId).filter((x): x is string => !!x));
      confirmados.forEach((id) => clearTimer(confirmTimers, id));
      const outbox = confirmados.size === 0 ? s.outbox : s.outbox.filter((o) => !confirmados.has(o.clientMessageId));
      return { messages: mergeChatMessages(s.messages, incoming), outbox };
    }),

  setLoadStatus: (loadStatus) => set({ loadStatus }),
  setError: (error) => set({ error }),
  setSendError: (sendError) => set({ sendError }),
  setConnected: (connected) => set({ connected }),

  addOutgoing: (message) => {
    set((s) => ({ outbox: [...s.outbox.filter((o) => o.clientMessageId !== message.clientMessageId), message] }));
    if (message.status === "sending") armarSeguroDeConfirmacion(message.clientMessageId);
  },

  markSending: (clientMessageId) => {
    set((s) => ({
      outbox: s.outbox.map((o) =>
        o.clientMessageId === clientMessageId ? { ...o, status: "sending", error: undefined } : o,
      ),
    }));
    armarSeguroDeConfirmacion(clientMessageId);
  },

  markFailed: (clientMessageId, error) => {
    clearTimer(confirmTimers, clientMessageId);
    set((s) => ({
      outbox: s.outbox.map((o) => (o.clientMessageId === clientMessageId ? { ...o, status: "failed", error } : o)),
    }));
  },

  failAllSending: (error) => {
    confirmTimers.forEach((t) => clearTimeout(t));
    confirmTimers.clear();
    set((s) =>
      s.outbox.some((o) => o.status === "sending")
        ? { outbox: s.outbox.map((o) => (o.status === "sending" ? { ...o, status: "failed", error } : o)) }
        : s,
    );
  },

  setAiTyping: (forUserId, forUserName, active) => {
    clearTimer(typingTimers, forUserId);
    if (active) {
      // Seguro: si nunca llega active=false, se apaga solo a los 45 s.
      typingTimers.set(
        forUserId,
        setTimeout(() => {
          typingTimers.delete(forUserId);
          get().setAiTyping(forUserId, forUserName, false);
        }, AI_TYPING_SEGURO_MS),
      );
      set((s) => ({ aiTyping: { ...s.aiTyping, [forUserId]: forUserName } }));
    } else {
      set((s) => {
        if (!(forUserId in s.aiTyping)) return s;
        const resto = { ...s.aiTyping };
        delete resto[forUserId];
        return { aiTyping: resto };
      });
    }
  },
}));

function armarSeguroDeConfirmacion(clientMessageId: string) {
  clearTimer(confirmTimers, clientMessageId);
  confirmTimers.set(
    clientMessageId,
    setTimeout(() => {
      confirmTimers.delete(clientMessageId);
      const o = useChatStore.getState().outbox.find((x) => x.clientMessageId === clientMessageId);
      if (o?.status === "sending") {
        useChatStore.getState().markFailed(clientMessageId, "El servidor no confirmó el envío.");
      }
    }, CONFIRMACION_SEGURO_MS),
  );
}
