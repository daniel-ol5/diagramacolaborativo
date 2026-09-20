import { createContext, useContext } from "react";
import type { Attribute, Method } from "../types/canonical";

/**
 * Subconjunto de UseCollaborationResult que UmlClassNode necesita para el
 * candado y las mutaciones de una clase. Se pasa por Context (no por prop de
 * nodo de React Flow) porque estas funciones son del cierre de la unica
 * instancia de useCollaboration que vive en App — no algo que toFlowNodes
 * pueda producir.
 */
export interface CollaborationActions {
  requestLock: (targetId: string) => void;
  releaseLock: (targetId: string) => void;
  renewLock: (targetId: string) => void;
  renameClass: (targetId: string, name: string) => void;
  deleteClass: (classId: string) => void;
  /** Ultimo-que-escribe-gana, igual que MOVE_CLASS -- no pide lock. */
  resizeClass: (classId: string, width: number, height: number) => void;
  flushResize: (classId: string) => void;
  addAttribute: (classId: string, attribute: Attribute) => void;
  updateAttribute: (attribute: Attribute) => void;
  deleteAttribute: (attrId: string) => void;
  addMethod: (classId: string, method: Method) => void;
  updateMethod: (method: Method) => void;
  deleteMethod: (methodId: string) => void;
  deleteRelationship: (relId: string) => void;
}

export const CollaborationActionsContext = createContext<CollaborationActions | null>(null);

export function useCollaborationActions(): CollaborationActions | null {
  return useContext(CollaborationActionsContext);
}
