import { createContext, useContext, useEffect, useMemo, useRef } from "react";
import { Client, type IFrame, type IMessage } from "@stomp/stompjs";
import { WS_URL } from "../../services/config";
import type { StompErrorMessage } from "../../types/collaboration";

/**
 * Conexion STOMP compartida entre el lienzo (useCollaboration) y el chat del
 * proyecto (CU19). Hay UNA sola conexion por (diagramId, token): el chat no
 * abre otra, se cuelga de esta.
 *
 * Antes, el `new Client(...)` vivia dentro de useCollaboration. Se saco aca
 * sin cambiar su configuracion (reconexion 3 s, latidos 4000/4000, JWT en el
 * CONNECT) ni el comportamiento del lienzo: el lienzo registra sus handlers
 * con la clave "canvas" y los cuerpos de esos handlers son los mismos de antes.
 */

/** Callbacks de un interesado en la conexion. Todos opcionales. */
export interface StompHandlers {
  /** En cada CONNECT (el primero y cada reconexion): las suscripciones se rehacen aca. */
  onConnect?(client: Client): void;
  onStompError?(frame: IFrame): void;
  onWebSocketError?(event: Event): void;
  onWebSocketClose?(event: CloseEvent): void;
  onDisconnect?(frame: IFrame): void;
  /** Un error de /user/queue/errors que es del chat (ver esErrorDelChat). */
  onChatError?(error: StompErrorMessage): void;
}

export interface StompConnection {
  /** El cliente vivo, o null si todavia no se activo (o no hay diagrama/token). */
  getClient(): Client | null;
  /**
   * Registra handlers por clave ("canvas", "chat"). La misma clave reemplaza
   * a la anterior. No dispara onConnect por su cuenta: si el que registra
   * llega con la conexion ya abierta, mira getClient()?.connected el mismo.
   * Devuelve la funcion que lo da de baja (solo si sigue siendo ese handler).
   */
  setHandlers(key: string, handlers: StompHandlers): () => void;
  /**
   * Reparte un mensaje de /user/queue/errors. Los del chat van a onChatError;
   * TODO lo demas (incluido un cuerpo que no es JSON o sin destination) va a
   * `canvasHandler(message)` con el mismo IMessage original, como siempre.
   */
  routeError(message: IMessage, canvasHandler: (message: IMessage) => void): void;
}

const PREFIJO_CHAT_SEND = "/app/project/";
const PREFIJO_CHAT_TOPIC = "/topic/projects/";

/**
 * Si un error de /user/queue/errors es del chat, segun su `destination`.
 *
 * El backend pone en `destination` el destino del frame rechazado:
 * - ChatStompController (rechazo del servicio, projectId invalido, y cuerpo
 *   ilegible SIN clientMutationId): "/app/project/{projectId}/chat".
 * - StompChannelInterceptor: el destino crudo del frame, o sea
 *   "/app/project/{id}/chat" (SEND) o "/topic/projects/..." (SUBSCRIBE).
 *
 * Solo esos dos prefijos son del chat; cualquier otra cosa es del lienzo.
 *
 * Ejemplos:
 *   esErrorDelChat("/app/project/7f3c.../chat")       -> true
 *   esErrorDelChat("/topic/projects/7f3c.../chat")    -> true
 *   esErrorDelChat("/topic/projects/**")              -> true
 *   esErrorDelChat("/app/diagram/55555555-.../mutate") -> false  (lienzo)
 *   esErrorDelChat("/app/diagram/55555555-.../lock")   -> false  (lienzo)
 *   esErrorDelChat("/topic/**")                       -> false  (comodin fuera del prefijo: lienzo)
 *   esErrorDelChat("/app/projects/x/chat")            -> false  (no es el prefijo exacto)
 *   esErrorDelChat(undefined) / (null) / (42) / ("")  -> false
 */
export function esErrorDelChat(destination: unknown): boolean {
  return (
    typeof destination === "string" &&
    (destination.startsWith(PREFIJO_CHAT_SEND) || destination.startsWith(PREFIJO_CHAT_TOPIC))
  );
}

interface Generation {
  client: Client;
  /** Copia propia: este cliente sigue avisando a SUS handlers hasta cerrarse. */
  handlers: Map<string, StompHandlers>;
}

export const StompConnectionContext = createContext<StompConnection | null>(null);

/**
 * Crea y mantiene el Client para (diagramId, token).
 *
 * Una sola conexion aunque StrictMode monte los efectos dos veces: el
 * efecto NO crea el cliente, programa su creacion+activacion con un
 * setTimeout(0) y la limpieza hace clearTimeout. StrictMode desmonta y
 * vuelve a montar los efectos de forma sincrona dentro del mismo commit,
 * asi que el primer temporizador se cancela antes de dispararse y solo
 * llega a existir un Client.
 *
 * Cada cliente toma una copia de los handlers registrados al crearse (y de
 * los que se registren mientras es el actual) y la conserva hasta cerrarse:
 * asi el onWebSocketClose tardio del cliente viejo, al salir de un
 * diagrama, le sigue llegando al handler del lienzo que tenia, igual que
 * antes cuando el callback vivia en el cierre del efecto.
 */
export function useStompConnectionCore(diagramId: string | null, token: string | null): StompConnection {
  const registry = useRef(new Map<string, StompHandlers>());
  const current = useRef<Generation | null>(null);

  const api = useMemo<StompConnection>(
    () => ({
      getClient: () => current.current?.client ?? null,
      setHandlers(key, handlers) {
        registry.current.set(key, handlers);
        current.current?.handlers.set(key, handlers);
        return () => {
          if (registry.current.get(key) === handlers) registry.current.delete(key);
          const vivos = current.current?.handlers;
          if (vivos?.get(key) === handlers) vivos.delete(key);
        };
      },
      routeError(message, canvasHandler) {
        let body: unknown;
        try {
          body = JSON.parse(message.body);
        } catch {
          canvasHandler(message);
          return;
        }
        const destination =
          body && typeof body === "object" ? (body as { destination?: unknown }).destination : undefined;
        if (!esErrorDelChat(destination)) {
          canvasHandler(message);
          return;
        }
        const handlers = current.current?.handlers;
        let entregado = false;
        handlers?.forEach((h) => {
          if (h.onChatError) {
            entregado = true;
            h.onChatError(body as StompErrorMessage);
          }
        });
        // Con el panel del chat abierto lo recibe useProjectChat (onChatError);
        // con el panel cerrado no hay nadie escuchando y queda en la consola.
        if (!entregado) console.warn("[chat] error del chat con el panel cerrado:", message.body);
      },
    }),
    [],
  );

  useEffect(() => {
    if (!diagramId || !token) return;

    let gen: Generation | null = null;
    const timer = setTimeout(() => {
      const handlers = new Map(registry.current);
      const client = new Client({
        brokerURL: WS_URL,
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 3000,
        // Sin esto, una conexion "muerta en silencio" puede quedar como
        // "conectado" indefinidamente (ver el comentario historico en
        // useCollaboration): los latidos hacen que stomp.js lo note solo.
        heartbeatIncoming: 4000,
        heartbeatOutgoing: 4000,
        onConnect: () => handlers.forEach((h) => h.onConnect?.(client)),
        onStompError: (frame) => handlers.forEach((h) => h.onStompError?.(frame)),
        onWebSocketError: (event) => handlers.forEach((h) => h.onWebSocketError?.(event)),
        onWebSocketClose: (event) => handlers.forEach((h) => h.onWebSocketClose?.(event)),
        onDisconnect: (frame) => handlers.forEach((h) => h.onDisconnect?.(frame)),
      });
      gen = { client, handlers };
      current.current = gen;
      client.activate();
    }, 0);

    return () => {
      clearTimeout(timer);
      if (gen) {
        if (current.current === gen) current.current = null;
        void gen.client.deactivate();
      }
    };
  }, [diagramId, token]);

  return api;
}

/** La conexion del StompConnectionProvider; null fuera de el. */
export function useStompConnection(): StompConnection | null {
  return useContext(StompConnectionContext);
}

/**
 * La del provider si hay uno (pantalla real de un diagrama); si no (/demo),
 * crea una propia con la misma logica. Los dos hooks se llaman siempre.
 */
export function useStompConnectionOrOwn(diagramId: string | null, token: string | null): StompConnection {
  const shared = useContext(StompConnectionContext);
  const own = useStompConnectionCore(shared ? null : diagramId, shared ? null : token);
  return shared ?? own;
}
