import { create } from "zustand";
import type { Attribute, CanonicalModel, ClassEntity, Method, Relationship } from "../types/canonical";

/**
 * Slice del estado CANONICO: lo que viaja al servidor y se persiste en el
 * JSONB de `diagrams.current_state` (§7, §13). Separado a proposito de
 * `presentationStore` — ver D-09: cuando entra una mutacion remota por
 * STOMP (Dia 8), hay que poder reemplazar este slice sin tocar seleccion,
 * paneles abiertos ni zoom. Si viviera en el mismo store, cada cambio ajeno
 * le patearia la interfaz al usuario en la cara.
 *
 * Cada accion de aca espeja exactamente la semantica de
 * backend/.../CanonicalMutationApplier.java para la misma operacion — se
 * usan tanto para el cambio local optimista como para aplicar la version
 * ajena que llega por /topic/diagrams/{id} (ver useCollaboration).
 */
interface CanonicalState {
  model: CanonicalModel | null;
  setModel: (model: CanonicalModel) => void;
  updateClassPosition: (classId: string, x: number, y: number) => void;
  renameClass: (classId: string, name: string) => void;
  addClass: (classEntity: ClassEntity) => void;
  /** Cascada identica a borrarClase() del servidor: relaciones huerfanas y membresia de paquete. */
  deleteClass: (classId: string) => void;
  addAttribute: (classId: string, attribute: Attribute) => void;
  updateAttribute: (attribute: Attribute) => void;
  deleteAttribute: (attrId: string) => void;
  addMethod: (classId: string, method: Method) => void;
  updateMethod: (method: Method) => void;
  deleteMethod: (methodId: string) => void;
  addRelationship: (relationship: Relationship) => void;
  deleteRelationship: (relId: string) => void;
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

  renameClass: (classId, name) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) => (c.id === classId ? { ...c, name } : c)),
        },
      };
    }),

  addClass: (classEntity) =>
    set((state) => {
      if (!state.model) return state;
      if (state.model.classes.some((c) => c.id === classEntity.id)) return state;
      return { model: { ...state.model, classes: [...state.model.classes, classEntity] } };
    }),

  deleteClass: (classId) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.filter((c) => c.id !== classId),
          relationships: state.model.relationships.filter(
            (r) => r.sourceClassId !== classId && r.targetClassId !== classId,
          ),
          packages: state.model.packages?.map((p) =>
            p.classIds.includes(classId)
              ? { ...p, classIds: p.classIds.filter((id) => id !== classId) }
              : p,
          ),
        },
      };
    }),

  addAttribute: (classId, attribute) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.id === classId ? { ...c, attributes: [...c.attributes, attribute] } : c,
          ),
        },
      };
    }),

  updateAttribute: (attribute) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.attributes.some((a) => a.id === attribute.id)
              ? { ...c, attributes: c.attributes.map((a) => (a.id === attribute.id ? attribute : a)) }
              : c,
          ),
        },
      };
    }),

  deleteAttribute: (attrId) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.attributes.some((a) => a.id === attrId)
              ? { ...c, attributes: c.attributes.filter((a) => a.id !== attrId) }
              : c,
          ),
        },
      };
    }),

  addMethod: (classId, method) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.id === classId ? { ...c, methods: [...c.methods, method] } : c,
          ),
        },
      };
    }),

  updateMethod: (method) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.methods.some((m) => m.id === method.id)
              ? { ...c, methods: c.methods.map((m) => (m.id === method.id ? method : m)) }
              : c,
          ),
        },
      };
    }),

  deleteMethod: (methodId) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          classes: state.model.classes.map((c) =>
            c.methods.some((m) => m.id === methodId)
              ? { ...c, methods: c.methods.filter((m) => m.id !== methodId) }
              : c,
          ),
        },
      };
    }),

  addRelationship: (relationship) =>
    set((state) => {
      if (!state.model) return state;
      if (state.model.relationships.some((r) => r.id === relationship.id)) return state;
      return { model: { ...state.model, relationships: [...state.model.relationships, relationship] } };
    }),

  deleteRelationship: (relId) =>
    set((state) => {
      if (!state.model) return state;
      return {
        model: {
          ...state.model,
          relationships: state.model.relationships.filter((r) => r.id !== relId),
        },
      };
    }),
}));
