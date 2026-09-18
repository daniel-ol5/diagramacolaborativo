import { create } from "zustand";

/**
 * Slice de PRESENTACION: zoom, nodo seleccionado, panel abierto — nada de
 * esto viaja al servidor ni se persiste en el canonico, y es solo tuyo (ni
 * siquiera compartido con los demas colaboradores). Separado de
 * `canonicalStore` a proposito (D-09). Los cursores/locks/roster de otros
 * colaboradores NO van aca — son compartidos pero efimeros, y viven en
 * `collaborationStore`, un tercer slice distinto de este.
 */
export type ViewMode = "uml" | "er";

interface PresentationState {
  selectedNodeId: string | null;
  selectNode: (id: string | null) => void;
  /** CU15: la misma notación UML o Entidad-Relación sobre el mismo modelo canonico, sin convertir nada -- por eso es presentacion y no un dato compartido. */
  viewMode: ViewMode;
  setViewMode: (mode: ViewMode) => void;
}

export const usePresentationStore = create<PresentationState>((set) => ({
  selectedNodeId: null,
  selectNode: (id) => set({ selectedNodeId: id }),
  viewMode: "uml",
  setViewMode: (mode) => set({ viewMode: mode }),
}));
