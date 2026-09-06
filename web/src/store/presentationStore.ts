import { create } from "zustand";

/**
 * Slice de PRESENTACION: zoom, nodo seleccionado, panel abierto — nada de
 * esto viaja al servidor ni se persiste en el canonico, y es solo tuyo (ni
 * siquiera compartido con los demas colaboradores). Separado de
 * `canonicalStore` a proposito (D-09). Los cursores/locks/roster de otros
 * colaboradores NO van aca — son compartidos pero efimeros, y viven en
 * `collaborationStore`, un tercer slice distinto de este.
 */
interface PresentationState {
  selectedNodeId: string | null;
  selectNode: (id: string | null) => void;
}

export const usePresentationStore = create<PresentationState>((set) => ({
  selectedNodeId: null,
  selectNode: (id) => set({ selectedNodeId: id }),
}));
