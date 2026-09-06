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
}));
