import type { OfflineOperation } from "../types/offline";

/**
 * Cola de mutaciones offline en IndexedDB (§9.2, punto 3 de la lista de
 * retome en docs/dias/dia-08_2026-09-10.md): en memoria, un F5 accidental
 * sin conexion borraria el trabajo que el modo offline promete no perder.
 */

const DB_NAME = "modelcollab-offline";
const DB_VERSION = 1;
const STORE = "operations";
const INDEX_DIAGRAM = "diagramId";

export interface QueuedOperation extends OfflineOperation {
  id?: number;
  diagramId: string;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: "id", autoIncrement: true });
        store.createIndex(INDEX_DIAGRAM, "diagramId");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function enqueueOperation(entry: Omit<QueuedOperation, "id">): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).add(entry);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

/** En el orden en que se encolaron (mismo orden de la clave primaria autoincremental). */
export async function getQueuedOperations(diagramId: string): Promise<QueuedOperation[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const request = tx.objectStore(STORE).index(INDEX_DIAGRAM).getAll(diagramId);
    request.onsuccess = () => resolve(request.result as QueuedOperation[]);
    request.onerror = () => reject(request.error);
  });
}

export async function countQueuedOperations(diagramId: string): Promise<number> {
  return (await getQueuedOperations(diagramId)).length;
}

/** Borra solo las entradas cuyo id esta en `ids` — para vaciar por lote a medida que cada uno se confirma. */
export async function removeQueuedOperations(ids: number[]): Promise<void> {
  if (ids.length === 0) return;
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    for (const id of ids) store.delete(id);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function clearQueuedOperations(diagramId: string): Promise<void> {
  const ops = await getQueuedOperations(diagramId);
  await removeQueuedOperations(ops.map((op) => op.id).filter((id): id is number => id != null));
}
