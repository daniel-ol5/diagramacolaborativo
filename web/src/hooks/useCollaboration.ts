import { useEffect, useRef, useState } from "react";
import { Client, type IMessage } from "@stomp/stompjs";
import { WS_URL } from "../services/config";
import { useCanonicalStore } from "../store/canonicalStore";
import { useCollaborationStore } from "../store/collaborationStore";
import { useOfflineStore } from "../store/offlineStore";
import type { Attribute, CanonicalModel, ClassEntity, Relationship } from "../types/canonical";
import type { OfflineOperation } from "../types/offline";
import {
  esBroadcastDeMutacion,
  esEventoDeSala,
  type LockDeniedMessage,
  type Participante,
  type StompErrorMessage,
} from "../types/collaboration";
import {
  countQueuedOperations,
  enqueueOperation,
  getQueuedOperations,
  removeQueuedOperations,
  type QueuedOperation,
} from "../services/offlineQueue";
import { syncOffline } from "../services/offlineApi";
import { fetchDiagramSnapshot } from "../services/diagramApi";

/** Tope por lote que exige el servidor (§9.2) — un lote mas grande se manda en varias tandas sucesivas. */
const MAX_OPERATIONS_PER_BATCH = 500;

export type CollaborationStatus = "desconectado" | "conectando" | "conectado" | "error";

export interface SequenceGap {
  esperaba: number;
  llego: number;
}

export interface UseCollaborationResult {
  status: CollaborationStatus;
  /**
   * Ultimo error: de conexion (onStompError/onWebSocketError) o de una
   * mutacion rechazada (LOCK_REQUIRED, DUPLICATE_ID, etc. por
   * /user/queue/errors). Se muestra independientemente de `status` — una
   * mutacion puede fallar sin que la conexion se caiga.
   */
  error: string | null;
  /** Descarta el error mostrado (la UI lo llama con un timeout, como los demas toasts). */
  clearError: () => void;
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
  /** Publica la posicion del cursor propio, con throttling. No es una mutacion: no toca el canonico. */
  sendCursor: (x: number, y: number, selectedId: string | null) => void;
  /** ADD_CLASS. No requiere lock (§8.1: "Raiz del diagrama"). El id lo genera el llamador (UUID v4). */
  addClass: (classEntity: ClassEntity) => void;
  /** DELETE_CLASS. Requiere lock sobre classId, adquirido antes por el llamador. */
  deleteClass: (classId: string) => void;
  /** ADD_ATTRIBUTE. Requiere lock sobre classId (la clase, no el atributo todavia inexistente). */
  addAttribute: (classId: string, attribute: Attribute) => void;
  /** UPDATE_ATTRIBUTE. Requiere lock sobre attribute.id — reemplazo completo, no parche. */
  updateAttribute: (attribute: Attribute) => void;
  /** DELETE_ATTRIBUTE. Requiere lock sobre attrId. */
  deleteAttribute: (attrId: string) => void;
  /** ADD_RELATIONSHIP. No requiere lock. El id lo genera el llamador. */
  addRelationship: (relationship: Relationship) => void;
  /** DELETE_RELATIONSHIP. Requiere lock sobre relId. */
  deleteRelationship: (relId: string) => void;
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
  const lastCursorSentAt = useRef(0);

  const setModel = useCanonicalStore((s) => s.setModel);
  const updateClassPosition = useCanonicalStore((s) => s.updateClassPosition);
  const renameClassLocal = useCanonicalStore((s) => s.renameClass);
  const addClassLocal = useCanonicalStore((s) => s.addClass);
  const deleteClassLocal = useCanonicalStore((s) => s.deleteClass);
  const addAttributeLocal = useCanonicalStore((s) => s.addAttribute);
  const updateAttributeLocal = useCanonicalStore((s) => s.updateAttribute);
  const deleteAttributeLocal = useCanonicalStore((s) => s.deleteAttribute);
  const addRelationshipLocal = useCanonicalStore((s) => s.addRelationship);
  const deleteRelationshipLocal = useCanonicalStore((s) => s.deleteRelationship);
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
      // Sin diagrama en vivo (demo local, o deslogueado) no hay "offline"
      // que mostrar: se resetea para no dejar un badge pegado de una sesion anterior.
      useOfflineStore.getState().setState("ONLINE");
      useOfflineStore.getState().setQueueLength(0);
      return;
    }

    setStatus("conectando");
    setError(null);
    setSequenceGap(null);
    lastSequenceNum.current = null;
    ownMutationIds.current.clear();
    refreshQueueLength();

    function refreshQueueLength() {
      countQueuedOperations(diagramId!).then((n) => useOfflineStore.getState().setQueueLength(n));
    }

    // §9.1: al reconectar DESPUES de haber estado OFFLINE (no en el primer
    // connect de la sesion) hay que mandar la cola pendiente y reconciliar
    // antes de volver a ONLINE. SYNCING es un estado propio para que el
    // usuario vea que hay un lote en camino, no un simple "ya se conecto".
    async function sincronizarColaPendiente() {
      const offline = useOfflineStore.getState();
      offline.setState("SYNCING");

      const pendientes = await getQueuedOperations(diagramId!);
      if (pendientes.length === 0) {
        offline.setState("ONLINE");
        return;
      }

      let aplicadas = 0;
      let descartadas = 0;
      const detalles: string[] = [];
      let ultimoSnapshot: CanonicalModel | null = null;

      try {
        for (let i = 0; i < pendientes.length; i += MAX_OPERATIONS_PER_BATCH) {
          const lote: QueuedOperation[] = pendientes.slice(i, i + MAX_OPERATIONS_PER_BATCH);
          const operaciones: OfflineOperation[] = lote.map(({ op, targetId, clientMutationId, payload, clientTimestamp }) => ({
            op,
            targetId,
            clientMutationId,
            payload,
            clientTimestamp,
          }));

          const reporte = await syncOffline(diagramId!, token!, lastSequenceNum.current, operaciones);

          aplicadas += reporte.appliedCount;
          descartadas += reporte.discardedCount;
          detalles.push(...reporte.discardedDetails);
          ultimoSnapshot = reporte.snapshot;
          lastSequenceNum.current = reporte.snapshot.mutationVersion;

          await removeQueuedOperations(
            lote.map((o) => o.id).filter((id): id is number => id != null),
          );
          refreshQueueLength();
        }

        if (ultimoSnapshot) setModel(ultimoSnapshot);
        offline.setReconciliationReport({
          appliedCount: aplicadas,
          discardedCount: descartadas,
          discardedDetails: detalles,
          snapshot: ultimoSnapshot!,
        });
        offline.setState("RECONCILED");
      } catch (e) {
        // No se pudo mandar el lote (el servidor volvio a caer a mitad de
        // camino, por ejemplo): la cola queda intacta en IndexedDB — nada
        // se pierde — y se reintenta en la proxima reconexion.
        console.error("[colaboracion] fallo la reconciliacion offline:", e);
        offline.setState("OFFLINE");
      }
    }

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 3000,
      // Sin esto, una conexion "muerta en silencio" (el servidor se cayo
      // sin cerrar el socket prolijamente, o hay un router en el medio que
      // se traga los paquetes) puede quedar como "conectado" indefinidamente
      // — exactamente el peor fallo del modo offline: creer que se esta
      // guardando cuando no. Los latidos hacen que stomp.js lo note solo.
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        setStatus("conectado");

        const offlineState = useOfflineStore.getState().state;

        // El orden importa: primero TODAS las suscripciones, recien despues
        // el pedido de roster. [cl1] probo empujarlo automaticamente al
        // conectar y hay una carrera con el registro de la suscripcion en
        // el broker que no se puede evitar desde el servidor — el cliente
        // es el unico que sabe cuando ya termino de suscribirse a todo.
        client.subscribe(`/topic/diagrams/${diagramId}`, (message) => handleTopic(message));
        client.subscribe("/user/queue/errors", (message) => handleError(message));
        client.subscribe("/user/queue/locks", (message) => handleLockDenied(message));
        client.subscribe("/user/queue/presence", (message) => handlePresenceResponse(message));

        client.publish({ destination: `/app/diagram/${diagramId}/presence`, body: "" });

        if (offlineState === "OFFLINE" || offlineState === "SYNCING") {
          void sincronizarColaPendiente();
        } else {
          useOfflineStore.getState().setState("ONLINE");
        }
      },
      onStompError: (frame) => {
        setStatus("error");
        setError(frame.headers?.message ?? "el servidor rechazo la conexion STOMP");
        useOfflineStore.getState().setState("OFFLINE");
      },
      onWebSocketError: () => {
        setStatus("error");
        setError("no se pudo abrir el WebSocket");
        useOfflineStore.getState().setState("OFFLINE");
      },
      // El hook real para "se cayo la red" (§9.1). `onDisconnect` de abajo
      // es para el DISCONNECT prolijo a nivel STOMP (client.deactivate()) —
      // un corte de verdad NUNCA manda ese frame, cierra el socket a secas.
      // Sin este handler especifico, cerrar el transporte no disparaba
      // ningun cambio de estado: lo confirme cerrando el WebSocket real de
      // la pagina de prueba (no con la emulacion de red offline de Chrome,
      // que no llega a afectar un socket ya abierto) y viendo que
      // `status` se quedaba en "conectado" para siempre.
      onWebSocketClose: () => {
        setStatus("desconectado");
        useOfflineStore.getState().setState("OFFLINE");
      },
      onDisconnect: () => {
        setStatus("desconectado");
        useOfflineStore.getState().setState("OFFLINE");
      },
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

    function aplicarRoster(participantes: Participante[]) {
      const store = useCollaborationStore.getState();
      store.setRoster(participantes.map((p) => ({ userId: p.userId, userName: p.userName, color: p.color })));
      store.pruneCursors(new Set(participantes.map((p) => p.userId)));
    }

    function handlePresenceResponse(message: IMessage) {
      try {
        const body = JSON.parse(message.body) as { participants: Participante[] };
        aplicarRoster(body.participants);
      } catch {
        console.warn("[colaboracion] respuesta de presencia no es JSON valido:", message.body);
      }
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

        switch (body.op) {
          case "MOVE_CLASS":
            if (body.targetId) updateClassPosition(body.targetId, Number(body.payload.x), Number(body.payload.y));
            break;
          case "RENAME_CLASS":
            if (body.targetId) renameClassLocal(body.targetId, String(body.payload.name));
            break;
          case "ADD_CLASS":
            addClassLocal(body.payload as unknown as ClassEntity);
            break;
          case "DELETE_CLASS":
            if (body.targetId) deleteClassLocal(body.targetId);
            break;
          case "ADD_ATTRIBUTE":
            if (body.targetId) addAttributeLocal(body.targetId, body.payload as unknown as Attribute);
            break;
          case "UPDATE_ATTRIBUTE":
            updateAttributeLocal(body.payload as unknown as Attribute);
            break;
          case "DELETE_ATTRIBUTE":
            if (body.targetId) deleteAttributeLocal(body.targetId);
            break;
          case "ADD_RELATIONSHIP":
            addRelationshipLocal(body.payload as unknown as Relationship);
            break;
          case "DELETE_RELATIONSHIP":
            if (body.targetId) deleteRelationshipLocal(body.targetId);
            break;
          case "BULK_MERGE":
            // CU17 (importar desde foto, confirmar): un solo evento con el
            // modelo canonico ENTERO ya fusionado, no una operacion sobre
            // un elemento -- mismo motivo que OFFLINE_SYNC: entran varias
            // clases y relaciones de un saque, y reemitirlas una por una
            // haria parpadear el lienzo de todos los demas colaboradores
            // (VisionImportService.confirmar). Se reemplaza el canonico
            // entero, no se aplica como un parche.
            setModel(body.payload as unknown as CanonicalModel);
            break;
          default:
            console.warn(`[colaboracion] operacion "${body.op}" ajena todavia no se aplica en este cliente`);
        }
        return;
      }

      if (esEventoDeSala(body)) {
        const store = useCollaborationStore.getState();
        switch (body.event) {
          case "LOCK_ACQUIRED":
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
            break;

          case "LOCK_RELEASED": {
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
            break;
          }

          case "USER_CURSOR":
            // El propio cursor no se guarda ni se dibuja: el mouse real ya
            // le muestra al usuario donde esta, y pintar el eco redondeado
            // solo agregaria un salto fantasma detras del puntero real.
            if (body.userId !== store.myUserId) {
              store.setCursor({
                userId: body.userId,
                userName: body.userName,
                color: body.color,
                x: body.x,
                y: body.y,
                selectedId: body.selectedId,
              });
            }
            break;

          case "PRESENCE":
            aplicarRoster(body.participants);
            break;

          case "OFFLINE_SYNC":
            // Un compañero reconcilio su cola offline: un solo evento con
            // el estado completo, no N mutaciones (asi lo emite el
            // servidor a proposito, para no parpadear veinte veces). Se
            // reemplaza el canonico entero, no se reproduce nada.
            setModel(body.snapshot);
            lastSequenceNum.current = body.sequenceNum;
            break;
        }
        return;
      }
    }

    // Cuando el servidor rechaza una mutacion (LOCK_REQUIRED, DUPLICATE_ID,
    // TARGET_NOT_FOUND, etc.) el emisor ya la aplico de forma optimista y
    // se quedaria mirando un estado que el servidor nunca tuvo. No se
    // deshace localmente (una mutacion ajena pudo llegar en el medio y
    // deshacer la instantanea previa se la llevaria puesta sin avisar):
    // se vuelve a pedir el snapshot real, que queda correcto pase lo que
    // pase mientras tanto, y de paso resincroniza mutationVersion.
    async function resincronizarTrasRechazo() {
      if (!diagramId || !token) return;
      try {
        const snapshot = await fetchDiagramSnapshot(diagramId, token);
        setModel(snapshot);
        lastSequenceNum.current = snapshot.mutationVersion;
      } catch (e) {
        console.error("[colaboracion] no se pudo resincronizar tras un rechazo:", e);
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
      void resincronizarTrasRechazo();
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
  }, [
    diagramId,
    token,
    setModel,
    updateClassPosition,
    renameClassLocal,
    addClassLocal,
    deleteClassLocal,
    addAttributeLocal,
    updateAttributeLocal,
    deleteAttributeLocal,
    addRelationshipLocal,
    deleteRelationshipLocal,
  ]);

  function publishMutate(op: string, targetId: string | null, payload: Record<string, unknown>) {
    if (!diagramId) return;
    const client = clientRef.current;
    const clientMutationId = crypto.randomUUID();

    if (!client?.connected) {
      // OFFLINE (§8.4): se encola en vez de perderse — incluidas las
      // operaciones que normalmente exigen lock, porque sin conexion no
      // hay a quien pedirselo. clientMutationId es obligatorio aca (al
      // reves que en el mensaje STOMP): es lo unico que hace seguro
      // reenviar el lote entero si la sincronizacion se corta a la mitad.
      void enqueueOperation({
        diagramId,
        op,
        targetId,
        clientMutationId,
        payload,
        clientTimestamp: Date.now(),
      }).then(() => countQueuedOperations(diagramId).then((n) => useOfflineStore.getState().setQueueLength(n)));
      return;
    }

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

  function sendCursor(x: number, y: number, selectedId: string | null) {
    const client = clientRef.current;
    if (!diagramId || !client?.connected) return;
    const now = performance.now();
    if (now - lastCursorSentAt.current < THROTTLE_MS) return;
    lastCursorSentAt.current = now;
    client.publish({
      destination: `/app/diagram/${diagramId}/cursor`,
      body: JSON.stringify({ x, y, selectedId }),
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

  function addClass(classEntity: ClassEntity) {
    addClassLocal(classEntity);
    publishMutate("ADD_CLASS", null, classEntity as unknown as Record<string, unknown>);
  }

  function deleteClass(classId: string) {
    useCollaborationStore.getState().setEditingTargetId(null);
    deleteClassLocal(classId);
    publishMutate("DELETE_CLASS", classId, {});
  }

  function addAttribute(classId: string, attribute: Attribute) {
    useCollaborationStore.getState().setEditingTargetId(null);
    addAttributeLocal(classId, attribute);
    publishMutate("ADD_ATTRIBUTE", classId, attribute as unknown as Record<string, unknown>);
  }

  function updateAttribute(attribute: Attribute) {
    useCollaborationStore.getState().setEditingTargetId(null);
    updateAttributeLocal(attribute);
    publishMutate("UPDATE_ATTRIBUTE", attribute.id, attribute as unknown as Record<string, unknown>);
  }

  function deleteAttribute(attrId: string) {
    useCollaborationStore.getState().setEditingTargetId(null);
    deleteAttributeLocal(attrId);
    publishMutate("DELETE_ATTRIBUTE", attrId, {});
  }

  function addRelationship(relationship: Relationship) {
    addRelationshipLocal(relationship);
    publishMutate("ADD_RELATIONSHIP", null, relationship as unknown as Record<string, unknown>);
  }

  function deleteRelationship(relId: string) {
    useCollaborationStore.getState().setEditingTargetId(null);
    deleteRelationshipLocal(relId);
    publishMutate("DELETE_RELATIONSHIP", relId, {});
  }

  return {
    status,
    error,
    clearError: () => setError(null),
    sequenceGap,
    moveClass,
    flushMove,
    requestLock,
    releaseLock,
    renewLock,
    renameClass,
    sendCursor,
    addClass,
    deleteClass,
    addAttribute,
    updateAttribute,
    deleteAttribute,
    addRelationship,
    deleteRelationship,
  };
}
