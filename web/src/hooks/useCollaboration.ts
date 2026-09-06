import { useEffect, useRef, useState } from "react";
import { Client, type IMessage } from "@stomp/stompjs";
import { WS_URL } from "../services/config";
import { useCanonicalStore } from "../store/canonicalStore";
import { useCollaborationStore } from "../store/collaborationStore";
import {
  esBroadcastDeMutacion,
  esEventoDeLock,
  type LockDeniedMessage,
  type StompErrorMessage,
} from "../types/collaboration";

export type CollaborationStatus = "desconectado" | "conectando" | "conectado" | "error";

export interface SequenceGap {
  esperaba: number;
  llego: number;
}

export interface UseCollaborationResult {
  status: CollaborationStatus;
  error: string | null;
  sequenceGap: SequenceGap | null;
  /**
   * Mueve una clase durante el arrastre: aplica el cambio local de
   * inmediato (optimista) y, si hay conexion en vivo, publica el
   * MOVE_CLASS con throttling (~20/s).
   */
  moveClass: (classId: string, x: number, y: number) => void;
  /**
   * Al soltar el mouse. React Flow manda el evento de "dragging: false" SIN
   * posicion (ver NodeChange de reactflow) — la ultima posicion real ya
   * quedo aplicada en el canonico por moveClass, asi que esto la relee del
   * store y la manda sin throttle. Si se olvida este paso, la clase puede
   * quedar en el canonico un tick antes de donde se solto (el ultimo
   * throttle descartado) para todos los demas colaboradores.
   */
  flushMove: (classId: string) => void;
  /** Pide el soft-lock de un elemento. No abre nada por su cuenta: hay que esperar LOCK_ACQUIRED. */
  requestLock: (targetId: string) => void;
  /**
   * Suelta el lock deliberadamente (cancelar la edicion). Cierra el input
   * ANTES de mandar el RELEASE — asi, cuando vuelva el LOCK_RELEASED que esto
   * mismo provoca, `editingTargetId` ya no coincide y no se confunde con
   * haberlo perdido por vencimiento (§8.2 punto 5 vs punto 4).
   */
  releaseLock: (targetId: string) => void;
  /** Heartbeat cada 2000ms mientras el input sigue abierto (§8.2 punto 4). */
  renewLock: (targetId: string) => void;
  /** Envia el RENAME_CLASS. El servidor libera el lock solo al confirmarlo (ver CollaborationStompController). */
  renameClass: (targetId: string, name: string) => void;
}

const THROTTLE_MS = 50; // ~20 mensajes/seg durante el arrastre (Dia 8)

/**
 * Conexion STOMP a un diagrama. `diagramId` en null significa "no hay
 * colaboracion en vivo para lo que se esta mostrando" (los tres diagramas de
 * demo que no existen en el servidor): el hook no conecta nada y
 * `moveClass` solo aplica el cambio local.
 */
export function useCollaboration(
  diagramId: string | null,
  token: string | null,
  userId: string | null,
): UseCollaborationResult {
  const [status, setStatus] = useState<CollaborationStatus>("desconectado");
  const [error, setError] = useState<string | null>(null);
  const [sequenceGap, setSequenceGap] = useState<SequenceGap | null>(null);

  const clientRef = useRef<Client | null>(null);
  const ownMutationIds = useRef<Set<string>>(new Set());
  const lastSequenceNum = useRef<number | null>(null);
  const lastSentAt = useRef(0);

  const updateClassPosition = useCanonicalStore((s) => s.updateClassPosition);
  const renameClassLocal = useCanonicalStore((s) => s.renameClass);
  const setMyUserId = useCollaborationStore((s) => s.setMyUserId);

  useEffect(() => {
    setMyUserId(userId);
  }, [userId, setMyUserId]);

  // El set de estado sincrono acá adentro (oxlint lo marca) es intencional:
  // el efecto sincroniza justamente con el sistema externo que es la
  // conexion STOMP, y el status/error/sequenceGap tienen que resetearse en
  // el mismo instante en que cambia diagramId o token, no un render despues.
  useEffect(() => {
    if (!diagramId || !token) {
      setStatus("desconectado");
      return;
    }

    setStatus("conectando");
    setError(null);
    setSequenceGap(null);
    lastSequenceNum.current = null;
    ownMutationIds.current.clear();

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        setStatus("conectado");

        client.subscribe(`/topic/diagrams/${diagramId}`, (message) => handleTopic(message));
        client.subscribe("/user/queue/errors", (message) => handleError(message));
        client.subscribe("/user/queue/locks", (message) => handleLockDenied(message));
      },
      onStompError: (frame) => {
        setStatus("error");
        setError(frame.headers?.message ?? "el servidor rechazo la conexion STOMP");
      },
      onWebSocketError: () => {
        setStatus("error");
        setError("no se pudo abrir el WebSocket");
      },
      onDisconnect: () => setStatus("desconectado"),
    });

    function checkSequence(seq: number) {
      const esperado = lastSequenceNum.current == null ? null : lastSequenceNum.current + 1;
      if (esperado != null && seq !== esperado) {
        console.warn(
          `[colaboracion] salto de secuencia en el diagrama ${diagramId}: esperaba ${esperado}, llego ${seq} (se perdio al menos un evento)`,
        );
        setSequenceGap({ esperaba: esperado, llego: seq });
      }
      lastSequenceNum.current = seq;
    }

    function handleTopic(message: IMessage) {
      let body: unknown;
      try {
        body = JSON.parse(message.body);
      } catch {
        console.warn("[colaboracion] mensaje del topic no es JSON valido:", message.body);
        return;
      }

      if (esBroadcastDeMutacion(body)) {
        checkSequence(body.sequenceNum);

        if (body.clientMutationId && ownMutationIds.current.has(body.clientMutationId)) {
          // Es el eco de una mutacion propia: ya se aplico de forma optimista
          // al mandarla. Volver a aplicarla pisaria un estado mas nuevo (el
          // usuario siguio editando mientras el mensaje iba y volvia) y
          // produciria el parpadeo que el criterio del Dia 8 prohibe.
          ownMutationIds.current.delete(body.clientMutationId);
          return;
        }

        if (body.op === "MOVE_CLASS" && body.targetId) {
          updateClassPosition(body.targetId, Number(body.payload.x), Number(body.payload.y));
        } else if (body.op === "RENAME_CLASS" && body.targetId) {
          renameClassLocal(body.targetId, String(body.payload.name));
        } else {
          console.warn(`[colaboracion] operacion "${body.op}" ajena todavia no se aplica en este cliente`);
        }
        return;
      }

      if (esEventoDeLock(body)) {
        const store = useCollaborationStore.getState();
        if (body.event === "LOCK_ACQUIRED") {
          store.setLock({
            targetId: body.targetId,
            userId: body.userId ?? "",
            userName: body.userName ?? "",
            color: body.color ?? "#999",
            ttl: body.ttl ?? 5000,
          });
          if (body.userId && body.userId === store.myUserId) {
            store.setEditingTargetId(body.targetId);
          }
        } else if (body.event === "LOCK_RELEASED") {
          // Si todavia creia que este era mi lock abierto, el servidor lo
          // vencio sin que yo lo soltara (§8.2 punto 5): releaseLock() y
          // renameClass() limpian editingTargetId ANTES de mandar su
          // mensaje, asi que si sigue puesto acá es porque nadie de este
          // lado lo cerro a proposito.
          const wasMine = store.editingTargetId === body.targetId;
          store.clearLock(body.targetId);
          if (wasMine) {
            store.setEditingTargetId(null);
            store.setLostLockNotice(
              "se venció tu lock mientras editabas — el cambio no se envió, probá de nuevo",
            );
          }
        }
        return;
      }
    }

    function handleError(message: IMessage) {
      try {
        const body = JSON.parse(message.body) as StompErrorMessage;
        console.warn(`[colaboracion] ${body.code} en ${body.destination}: ${body.message}`);
        setError(`${body.code}: ${body.message}`);
      } catch {
        setError(message.body);
      }
    }

    function handleLockDenied(message: IMessage) {
      try {
        const body = JSON.parse(message.body) as LockDeniedMessage;
        console.info(`[colaboracion] LOCK_DENIED: ${body.targetId} en edicion por ${body.heldBy}`);
        useCollaborationStore.getState().setDeniedNotice({ targetId: body.targetId, heldBy: body.heldBy });
      } catch {
        console.info("[colaboracion] LOCK_DENIED:", message.body);
      }
    }

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [diagramId, token, updateClassPosition, renameClassLocal]);

  function publishMutate(op: string, targetId: string, payload: Record<string, unknown>) {
    const client = clientRef.current;
    if (!diagramId || !client?.connected) return;

    const clientMutationId = crypto.randomUUID();
    ownMutationIds.current.add(clientMutationId);
    client.publish({
      destination: `/app/diagram/${diagramId}/mutate`,
      body: JSON.stringify({ op, targetId, clientMutationId, payload }),
    });
  }

  function publishLock(targetId: string, action: "ACQUIRE" | "RELEASE" | "HEARTBEAT") {
    const client = clientRef.current;
    if (!diagramId || !client?.connected) return;
    client.publish({
      destination: `/app/diagram/${diagramId}/lock`,
      body: JSON.stringify({ action, targetId }),
    });
  }

  function moveClass(classId: string, x: number, y: number) {
    updateClassPosition(classId, x, y);

    const now = performance.now();
    if (now - lastSentAt.current < THROTTLE_MS) return;
    lastSentAt.current = now;
    publishMutate("MOVE_CLASS", classId, { x, y });
  }

  function flushMove(classId: string) {
    const clase = useCanonicalStore.getState().model?.classes.find((c) => c.id === classId);
    if (!clase) return;
    publishMutate("MOVE_CLASS", classId, { x: clase.position.x, y: clase.position.y });
  }

  function requestLock(targetId: string) {
    publishLock(targetId, "ACQUIRE");
  }

  function releaseLock(targetId: string) {
    useCollaborationStore.getState().setEditingTargetId(null);
    publishLock(targetId, "RELEASE");
  }

  function renewLock(targetId: string) {
    publishLock(targetId, "HEARTBEAT");
  }

  function renameClass(targetId: string, name: string) {
    useCollaborationStore.getState().setEditingTargetId(null);
    publishMutate("RENAME_CLASS", targetId, { name });
  }

  return {
    status,
    error,
    sequenceGap,
    moveClass,
    flushMove,
    requestLock,
    releaseLock,
    renewLock,
    renameClass,
  };
}
