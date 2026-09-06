import { create } from "zustand";

/**
 * Tercer slice, distinto de `canonicalStore` (el diagrama) y de
 * `presentationStore` (tu seleccion, tu zoom): locks, roster y cursores son
 * estado COMPARTIDO pero EFIMERO — no se persisten, un lock muere en 5s y
 * un cursor no sobrevive a la desconexion. Meterlos en `presentationStore`
 * mezclaria "lo tuyo" con "lo que otro te impone"; meterlos en
 * `canonicalStore` los haria parecer parte del diagrama que se persiste.
 */
export interface LockInfo {
  targetId: string;
  userId: string;
  userName: string;
  color: string;
  ttl: number;
}

export interface CursorInfo {
  userId: string;
  userName: string;
  color: string;
  x: number;
  y: number;
  selectedId: string | null;
}

export interface RosterEntry {
  userId: string;
  nombre: string;
  color: string;
}

interface CollaborationState {
  myUserId: string | null;
  setMyUserId: (id: string | null) => void;

  /** Un lock activo por targetId, tal como lo anuncio el servidor via LOCK_ACQUIRED. */
  locks: Record<string, LockInfo>;
  setLock: (info: LockInfo) => void;
  clearLock: (targetId: string) => void;

  /** El unico nodo, si hay alguno, cuyo input de edicion tengo abierto YO. */
  editingTargetId: string | null;
  setEditingTargetId: (targetId: string | null) => void;

  /** Ultimo LOCK_DENIED para mostrar como toast; se limpia solo desde la UI. */
  deniedNotice: { targetId: string; heldBy: string } | null;
  setDeniedNotice: (notice: { targetId: string; heldBy: string } | null) => void;

  /** Se puso "perdiste el lock" — el TTL vencio mientras vos creias tenerlo. */
  lostLockNotice: string | null;
  setLostLockNotice: (message: string | null) => void;

  /** Cursor ajeno mas reciente por userId (el propio nunca se guarda aca, ver useCollaboration). */
  cursors: Record<string, CursorInfo>;
  setCursor: (info: CursorInfo) => void;
  /** Saca los cursores de quien ya no esta en el roster (se fue sin un ultimo aviso de cursor). */
  pruneCursors: (userIdsPresentes: Set<string>) => void;

  /** Quienes estan en la sala ahora mismo (PRESENCE, lista completa siempre). */
  roster: RosterEntry[];
  setRoster: (roster: RosterEntry[]) => void;
}

export const useCollaborationStore = create<CollaborationState>((set) => ({
  myUserId: null,
  setMyUserId: (id) => set({ myUserId: id }),

  locks: {},
  setLock: (info) => set((state) => ({ locks: { ...state.locks, [info.targetId]: info } })),
  clearLock: (targetId) =>
    set((state) => {
      if (!(targetId in state.locks)) return state;
      const locks = { ...state.locks };
      delete locks[targetId];
      return { locks };
    }),

  editingTargetId: null,
  setEditingTargetId: (targetId) => set({ editingTargetId: targetId }),

  deniedNotice: null,
  setDeniedNotice: (notice) => set({ deniedNotice: notice }),

  lostLockNotice: null,
  setLostLockNotice: (message) => set({ lostLockNotice: message }),

  cursors: {},
  setCursor: (info) => set((state) => ({ cursors: { ...state.cursors, [info.userId]: info } })),
  pruneCursors: (userIdsPresentes) =>
    set((state) => {
      const entries = Object.entries(state.cursors).filter(([userId]) => userIdsPresentes.has(userId));
      if (entries.length === Object.keys(state.cursors).length) return state;
      return { cursors: Object.fromEntries(entries) };
    }),

  roster: [],
  setRoster: (roster) => set({ roster }),
}));
