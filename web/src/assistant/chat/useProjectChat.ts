import { useCallback, useEffect, useRef } from "react";
import type { Client, IMessage, StompSubscription } from "@stomp/stompjs";
import type { StompErrorMessage } from "../../types/collaboration";
import { useStompConnection } from "../shell/stompConnection";
import { ChatApiError, fetchChatHistory } from "./chatApi";
import { MAX_CHARS, textoDeErrorDelChat } from "./chatErrors";
import { lastSeqOf, useChatStore, type OutgoingMessage } from "./chatStore";
import { esChatMessage } from "./types";

/** Tamaño de la primera carga y de cada tanda de afterSeq. */
const PRIMERA_CARGA = 50;
const TANDA_POSTERIORES = 100;

const SIN_CONEXION = "Se perdió la conexión antes de confirmar el envío.";

export interface ProjectChatActions {
  /** Envía un mensaje nuevo. Devuelve false si no se pudo (sin conexión, vacío o muy largo). */
  send: (text: string) => boolean;
  /** Reintenta un propio rechazado con el MISMO clientMessageId. */
  retry: (clientMessageId: string) => void;
}

/**
 * Engancha el chat del proyecto a la conexion STOMP compartida (no abre
 * otra). En cada CONNECT (el primero y cada reconexion):
 *   1. SUBSCRIBE a /topic/projects/{projectId}/chat -- el destino exacto,
 *      sin comodines;
 *   2. DESPUES pide el historial: los ultimos 50 si no hay nada, o
 *      afterSeq=<ultimo seq> repitiendo mientras hasMore sea true.
 * Todo se fusiona por seq, asi que un mensaje que llega por el topic entre
 * el SUBSCRIBE y la respuesta del GET no se pierde ni se duplica.
 *
 * Envio: STOMP a /app/project/{projectId}/chat con {clientMessageId, text,
 * diagramId?}. El autor NO va en el cuerpo (sale del token; el backend
 * rechaza campos desconocidos). El chat NO usa la cola offline del diagrama.
 */
export function useProjectChat(projectId: string, token: string, diagramId: string | null): ProjectChatActions {
  const connection = useStompConnection();
  // La sincronizacion vive dentro del efecto (sabe si la carga sigue vigente);
  // retry la necesita desde afuera.
  const sincronizarRef = useRef<(() => Promise<void>) | null>(null);

  useEffect(() => {
    if (!connection) return;
    const store = useChatStore.getState();
    store.ensureProject(projectId);
    store.setConnected(connection.getClient()?.connected === true);

    let activo = true;
    let suscripcion: StompSubscription | null = null;
    // Cada CONNECT (o sincronizacion pedida) invalida la carga anterior en vuelo.
    let carga = 0;

    function alLlegar(message: IMessage) {
      let body: unknown;
      try {
        body = JSON.parse(message.body);
      } catch {
        console.warn("[chat] mensaje del topic no es JSON valido:", message.body);
        return;
      }
      const ev = body as { event?: unknown; message?: unknown; forUserId?: unknown; forUserName?: unknown; active?: unknown };
      if (ev.event === "CHAT_MESSAGE") {
        if (esChatMessage(ev.message)) useChatStore.getState().mergeMessages([ev.message]);
        else console.warn("[chat] CHAT_MESSAGE descartado por formato:", ev.message);
      } else if (ev.event === "AI_TYPING") {
        if (typeof ev.forUserId === "string" && typeof ev.active === "boolean") {
          const nombre = typeof ev.forUserName === "string" ? ev.forUserName : "";
          useChatStore.getState().setAiTyping(ev.forUserId, nombre, ev.active);
        }
      }
    }

    async function sincronizar(n: number) {
      const vigente = () => activo && n === carga;
      const s = useChatStore.getState();
      s.setLoadStatus("loading");
      s.setError(null);
      try {
        let desde = lastSeqOf(s.messages);
        if (desde == null) {
          const pagina = await fetchChatHistory(projectId, token, { limit: PRIMERA_CARGA });
          if (!vigente()) return;
          useChatStore.getState().mergeMessages(pagina.messages);
        } else {
          let hayMas = true;
          while (hayMas) {
            const pagina = await fetchChatHistory(projectId, token, { afterSeq: desde, limit: TANDA_POSTERIORES });
            if (!vigente()) return;
            useChatStore.getState().mergeMessages(pagina.messages);
            const ultimo = lastSeqOf(pagina.messages);
            // Sin mensajes nuevos no hay como avanzar el cursor: se corta.
            hayMas = pagina.hasMore && ultimo != null && ultimo > desde;
            if (ultimo != null) desde = ultimo;
          }
        }
        useChatStore.getState().setLoadStatus("ready");
      } catch (e) {
        if (!vigente()) return;
        const texto =
          e instanceof ChatApiError
            ? e.status === 403
              ? e.message
              : textoDeErrorDelChat(e.code, e.message)
            : "No se pudo cargar el historial del chat. Revisa tu conexión.";
        useChatStore.getState().setError(texto);
        useChatStore.getState().setLoadStatus("error");
      }
    }

    sincronizarRef.current = () => {
      carga += 1;
      return sincronizar(carga);
    };

    function alConectar(client: Client) {
      if (!activo) return;
      useChatStore.getState().setConnected(true);
      // Tras una reconexion la suscripcion vieja ya murio con el socket.
      suscripcion = client.subscribe(`/topic/projects/${projectId}/chat`, alLlegar);
      carga += 1;
      void sincronizar(carga);
    }

    function alCaer() {
      if (!activo) return;
      const s = useChatStore.getState();
      s.setConnected(false);
      // Lo que iba en camino queda "no se envió"; si en realidad llego, la
      // sincronizacion al reconectar lo confirma por clientMessageId.
      s.failAllSending(SIN_CONEXION);
    }

    function alRechazar(err: StompErrorMessage) {
      if (!activo) return;
      const s = useChatStore.getState();
      const texto = textoDeErrorDelChat(err.code, err.message);
      s.setSendError(texto);
      // clientMutationId lleva el clientMessageId del mensaje rechazado. Sin
      // el (p. ej. INVALID_PAYLOAD por cuerpo ilegible, o un SUBSCRIBE), es un
      // error general: no se asocia a ningun mensaje.
      const cmid = err.clientMutationId ?? undefined;
      if (cmid && s.outbox.some((o) => o.clientMessageId === cmid)) s.markFailed(cmid, texto);
    }

    const quitar = connection.setHandlers("chat", {
      onConnect: alConectar,
      onChatError: alRechazar,
      onWebSocketClose: alCaer,
      onDisconnect: alCaer,
      onStompError: alCaer,
      onWebSocketError: alCaer,
    });

    // Si el panel se abre con la conexion ya hecha, no va a haber otro
    // onConnect hasta la proxima reconexion: se engancha ahora. Diferido y
    // cancelable, igual que el cliente: con StrictMode el efecto se monta
    // dos veces y asi sale un solo SUBSCRIBE y un solo GET.
    const timer = setTimeout(() => {
      const client = connection.getClient();
      // Si en el medio ya entro un onConnect, ya hay suscripcion: no repetir.
      if (client?.connected && suscripcion == null) alConectar(client);
    }, 0);

    return () => {
      activo = false;
      clearTimeout(timer);
      quitar();
      sincronizarRef.current = null;
      // Sin suscripcion no llegaria el eco: lo pendiente queda reintentable.
      useChatStore.getState().failAllSending(SIN_CONEXION);
      if (suscripcion && connection.getClient()?.connected) {
        try {
          suscripcion.unsubscribe();
        } catch {
          // la conexion se cerro en el medio: la suscripcion ya no existe
        }
      }
      suscripcion = null;
    };
  }, [connection, projectId, token]);

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
      const sincronizar = sincronizarRef.current ?? (() => Promise.resolve());
      void sincronizar().then(() => {
        const sigue = useChatStore.getState().outbox.find((x) => x.clientMessageId === clientMessageId);
        if (sigue?.status !== "sending") return;
        if (!publicar(sigue)) useChatStore.getState().markFailed(clientMessageId, SIN_CONEXION);
      });
    },
    [publicar],
  );

  return { send, retry };
}
