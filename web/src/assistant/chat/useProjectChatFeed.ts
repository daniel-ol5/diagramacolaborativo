import { useEffect } from "react";
import type { Client, IMessage, StompSubscription } from "@stomp/stompjs";
import type { StompErrorMessage } from "../../types/collaboration";
import { useStompConnection } from "../shell/stompConnection";
import { fetchChatHistory, fetchReadState, putReadState } from "./chatApi";
import { textoDeErrorDeCarga, textoDeErrorDelChat } from "./chatErrors";
import { lastSeqOf, registrarSincronizador, useChatStore, vistaDe } from "./chatStore";
import { seqParaMarcarLeido } from "./unread";
import { esChatMessage } from "./types";

/** Tamaño de la primera carga y de cada tanda de afterSeq. */
const PRIMERA_CARGA = 50;
const TANDA_POSTERIORES = 100;
/** Espera antes de enviar el PUT /chat/read. */
const MARCAR_LEIDO_MS = 500;

export const SIN_CONEXION = "Se perdió la conexión antes de confirmar el envío.";

/**
 * Engancha el chat del proyecto a la conexion STOMP compartida (no abre
 * otra) MIENTRAS DiagramScreen este montado, con el panel abierto o cerrado:
 * asi se siguen recibiendo mensajes y se cuentan los no leidos.
 *
 * En cada CONNECT (el primero y cada reconexion):
 *   1. SUBSCRIBE a /topic/projects/{projectId}/chat -- el destino exacto,
 *      sin comodines;
 *   2. DESPUES pide el historial: los ultimos 50 si no hay nada, o
 *      afterSeq=<ultimo seq> repitiendo mientras hasMore sea true;
 *   3. y por ultimo GET /chat/read para sincronizar lastReadSeq y unreadCount.
 * Todo se fusiona por seq, asi que un mensaje que llega por el topic entre
 * el SUBSCRIBE y la respuesta del GET no se pierde ni se duplica.
 *
 * Ademas, con el panel abierto, la pestaña visible y la lista al final,
 * marca leido (PUT /chat/read) hasta el mayor seq, con 500 ms de espera.
 */
export function useProjectChatFeed(projectId: string, token: string, myUserId: string): void {
  const connection = useStompConnection();

  useEffect(() => {
    useChatStore.getState().setMyUserId(myUserId);
  }, [myUserId]);

  // Pestaña visible u oculta.
  useEffect(() => {
    const alCambiar = () => useChatStore.getState().setVisible(document.visibilityState === "visible");
    alCambiar();
    document.addEventListener("visibilitychange", alCambiar);
    return () => document.removeEventListener("visibilitychange", alCambiar);
  }, []);

  // Suscripcion al topic, historial y estado de lectura.
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
        if (esChatMessage(ev.message)) useChatStore.getState().receiveLive(ev.message);
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
          // Sin cursores el servidor da los ultimos 50: hasMore = hay mas viejos.
          // Si vino la pagina llena tambien puede haberlos; si no hay, el primer
          // "cargar anteriores" devuelve vacio y apaga el boton.
          useChatStore.getState().setOlder({
            hasOlder: pagina.hasMore || pagina.messages.length >= PRIMERA_CARGA,
            olderStatus: "idle",
            olderError: null,
          });
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
        const texto = textoDeErrorDeCarga(e, "No se pudo cargar el historial del chat. Revisa tu conexión.");
        useChatStore.getState().setError(texto);
        useChatStore.getState().setLoadStatus("error");
      }
    }

    async function sincronizarLectura(n: number) {
      try {
        const resp = await fetchReadState(projectId, token);
        if (activo && n === carga) useChatStore.getState().applyReadState(resp);
      } catch (e) {
        // El contador queda como estaba; el historial ya muestra su propio error.
        console.warn("[chat] no se pudo leer el estado de lectura:", e);
      }
    }

    registrarSincronizador(() => {
      carga += 1;
      return sincronizar(carga);
    });

    function alConectar(client: Client) {
      if (!activo) return;
      useChatStore.getState().setConnected(true);
      // Tras una reconexion la suscripcion vieja ya murio con el socket.
      suscripcion = client.subscribe(`/topic/projects/${projectId}/chat`, alLlegar);
      carga += 1;
      const n = carga;
      void sincronizar(n).then(() => sincronizarLectura(n));
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

    // Si el feed se monta con la conexion ya hecha, no va a haber otro
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
      registrarSincronizador(null);
      // Se sale del diagrama: sin suscripcion no llegaria el eco.
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

  // Marcar leido.
  useEffect(() => {
    let activo = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let programado: number | null = null;
    let enVuelo: number | null = null;

    function objetivo(): number | null {
      const s = useChatStore.getState();
      // Hasta saber el lastReadSeq del servidor no se marca nada.
      if (s.projectId !== projectId || s.lastReadSeq == null) return null;
      return seqParaMarcarLeido({
        vista: vistaDe(s),
        maxSeq: lastSeqOf(s.messages),
        lastReadSeq: s.lastReadSeq,
        enVuelo,
      });
    }

    async function enviar() {
      timer = undefined;
      programado = null;
      const upTo = objetivo();
      if (upTo == null) return;
      enVuelo = upTo;
      try {
        const resp = await putReadState(projectId, token, upTo);
        if (!activo) return;
        enVuelo = null;
        useChatStore.getState().applyReadState(resp);
      } catch (e) {
        enVuelo = null;
        console.warn("[chat] no se pudo marcar como leido:", e);
        return;
      }
      revisar();
    }

    function revisar() {
      if (!activo) return;
      const upTo = objetivo();
      if (upTo === programado) return; // ya hay un PUT programado para eso
      clearTimeout(timer);
      timer = undefined;
      programado = upTo;
      if (upTo != null) timer = setTimeout(() => void enviar(), MARCAR_LEIDO_MS);
    }

    const desuscribir = useChatStore.subscribe(revisar);
    revisar();
    return () => {
      activo = false;
      clearTimeout(timer);
      desuscribir();
    };
  }, [projectId, token]);
}
