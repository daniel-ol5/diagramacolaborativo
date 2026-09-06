import { create } from "zustand";
import type { CanonicalModel } from "../types/canonical";

/**
 * Slice del estado CANONICO: lo que viaja al servidor y se persiste en el
 * JSONB de `diagrams.current_state` (§7, §13). Separado a proposito de
 * `presentationStore` — ver D-09: cuando el Dia 8 traiga mutaciones remotas
 * por STOMP, hay que poder reemplazar este slice entero sin tocar seleccion,
 * paneles abiertos ni zoom. Si viviera en el mismo store, cada cambio ajeno
 * le patearia la interfaz al usuario en la cara.
 */
interface CanonicalState {
  model: CanonicalModel | null;
  setModel: (model: CanonicalModel) => void;
}

export const useCanonicalStore = create<CanonicalState>((set) => ({
  model: null,
  setModel: (model) => set({ model }),
}));
