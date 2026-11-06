import { useCallback } from "react";
import { useStompConnection } from "../shell/stompConnection";
import { MAX_CHARS } from "./chatErrors";
import { sincronizarChat, useChatStore, type OutgoingMessage } from "./chatStore";
import { SIN_CONEXION } from "./useProjectChatFeed";

export interface ProjectChatActions {
  /** Envía un mensaje nuevo. Devuelve false si no se pudo (sin conexión, vacío o muy largo). */
  send: (text: string) => boolean;
  /** Reintenta un propio rechazado con el MISMO clientMessageId. */
  retry: (clientMessageId: string) => void;
}

/**
 * Envio y reintento del chat del proyecto. La suscripcion, el historial y
 * los no leidos viven en useProjectChatFeed (montado en DiagramScreen); este
 * hook solo publica.
 *
 * Envio: STOMP a /app/project/{projectId}/chat con {clientMessageId, text,
 * diagramId?}. El autor NO va en el cuerpo (sale del token; el backend
 * rechaza campos desconocidos). El chat NO usa la cola offline del diagrama.
 */
export function useProjectChat(projectId: string, diagramId: string | null): ProjectChatActions {
  const connection = useStompConnection();

  /** Publica; false si no hay conexion. */
  const publicar = useCallback(
    (o: OutgoingMessage): boolean => {
      const client = connection?.getClient();
      if (!client?.connected) return false;
      client.publish({
        destination: `/app/project/${projectId}/chat`,
        // Solo estos campos: el autor sale del token.
        body: JSON.stringify({
          clientMessageId: o.clientMessageId,
          text: o.text,
          ...(o.diagramId ? { diagramId: o.diagramId } : {}),
        }),
      });
      return true;
    },
    [connection, projectId],
  );

  const send = useCallback(
    (raw: string): boolean => {
      const text = raw.trim();
      if (!text || text.length > MAX_CHARS) return false;
      const s = useChatStore.getState();
      if (!s.connected) return false;
      const outgoing: OutgoingMessage = {
        // UUID v4 en hex: nunca empieza por los prefijos reservados "ai-" ni "sys-".
        clientMessageId: crypto.randomUUID(),
        text,
        diagramId: diagramId ?? undefined,
        createdAt: new Date().toISOString(),
        status: "sending",
      };
      s.setSendError(null);
      s.addOutgoing(outgoing);
      if (!publicar(outgoing)) {
        useChatStore.getState().markFailed(outgoing.clientMessageId, SIN_CONEXION);
        return false;
      }
      return true;
    },
    [diagramId, publicar],
  );

  const retry = useCallback(
    (clientMessageId: string) => {
      const s = useChatStore.getState();
      const o = s.outbox.find((x) => x.clientMessageId === clientMessageId);
      if (!o || o.status !== "failed" || !s.connected) return;
      s.setSendError(null);
      s.markSending(clientMessageId);
      // Un clientMessageId que el servidor YA guardo no se vuelve a difundir
      // (ChatPostResult.duplicate): por eso primero se sincroniza; si el eco
      // aparece en el historial, queda confirmado y no se publica de nuevo.
      void sincronizarChat().then(() => {
        const sigue = useChatStore.getState().outbox.find((x) => x.clientMessageId === clientMessageId);
        if (sigue?.status !== "sending") return;
        if (!publicar(sigue)) useChatStore.getState().markFailed(clientMessageId, SIN_CONEXION);
      });
    },
    [publicar],
  );

  return { send, retry };
}
