import { createContext, useContext } from "react";

/**
 * Subconjunto de UseCollaborationResult que UmlClassNode necesita para el
 * candado y el rename. Se pasa por Context (no por prop de nodo de React
 * Flow) porque estas funciones son del cierre de la unica instancia de
 * useCollaboration que vive en App — no algo que toFlowNodes pueda producir.
 */
export interface CollaborationActions {
  requestLock: (targetId: string) => void;
  releaseLock: (targetId: string) => void;
  renewLock: (targetId: string) => void;
  renameClass: (targetId: string, name: string) => void;
}

export const CollaborationActionsContext = createContext<CollaborationActions | null>(null);

export function useCollaborationActions(): CollaborationActions | null {
  return useContext(CollaborationActionsContext);
}
