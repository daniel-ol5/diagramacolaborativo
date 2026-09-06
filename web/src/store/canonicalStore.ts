import { create } from "zustand";
import type { CanonicalModel } from "../types/canonical";

/**
 * Slice del estado CANONICO: lo que viaja al servidor y se persiste en el
 * JSONB de `diagrams.current_state` (§7, §13). Separado a proposito de
 * `presentationStore` — ver D-09: cuando entra una mutacion remota por
 * STOMP (Dia 8), hay que poder reemplazar este slice sin tocar seleccion,
 * paneles abiertos ni zoom. Si viviera en el mismo store, cada cambio ajeno
 * le patearia la interfaz al usuario en la cara.
 */
interface CanonicalState {
  model: CanonicalModel | null;
  setModel: (model: CanonicalModel) => void;
  /**
   * Usado tanto por el arrastre local (optimista, antes de que el servidor
   * confirme nada) como al aplicar un MOVE_CLASS ajeno que llega por
   * /topic/diagrams/{id}. Es la misma operacion en los dos casos: mover una
   * clase a una posicion dada.
   */
  updateClassPosition: (classId: string, x: number, y: number) => void;
}

export const useCanonicalStore = create<CanonicalState>((set) => ({
  model: null,
  setModel: (model) => set({ model }),
  updateClassPosition: (classId, x, y) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.id === classId ? { ...c, position: { x, y } } : c,
          ),
        },
      };
    }),
}));
