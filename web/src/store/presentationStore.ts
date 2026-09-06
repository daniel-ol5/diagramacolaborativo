import { create } from "zustand";

/**
 * Slice de PRESENTACION: zoom, nodo seleccionado, panel abierto, cursores
 * ajenos — nada de esto viaja al servidor ni se persiste en el canonico.
 * Separado de `canonicalStore` a proposito (D-09, ver ese archivo).
 */
interface PresentationState {
  selectedNodeId: string | null;
  selectNode: (id: string | null) => void;
}

export const usePresentationStore = create<PresentationState>((set) => ({
  selectedNodeId: null,
  selectNode: (id) => set({ selectedNodeId: id }),
}));
