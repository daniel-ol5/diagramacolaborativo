import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent } from "react";
import { Handle, Position, type NodeProps } from "reactflow";
import type { ClassEntity } from "../types/canonical";
import { visibilitySymbol, methodSignature } from "../lib/uml";
import { useCollaborationActions } from "../hooks/collaborationActionsContext";
import { useCollaborationStore } from "../store/collaborationStore";
import "./UmlClassNode.css";

/**
 * Nodo React Flow que dibuja una clase UML con su notacion estandar: tres
 * compartimentos (nombre / atributos / metodos), nombre en cursiva si es
 * abstracta, simbolos de visibilidad y atributos PK subrayados. Ademas el
 * nombre se puede renombrar bajo soft-lock (§8.2, Dia 9): ver
 * `collaborationStore` para el porque de un tercer store.
 *
 * Los Handle son invisibles y no se usan para su posicion: React Flow se
 * niega a crear una arista si el nodo no tiene al menos un handle "source" y
 * uno "target" (advertencia #008), aunque el path lo calcule por su cuenta
 * `UmlRelationshipEdge` a partir de `position`/`width`/`height` del
 * canonico. Crear conexiones arrastrando no esta implementado.
 */
export function UmlClassNode({ data, selected }: NodeProps<ClassEntity>) {
  const lock = useCollaborationStore((s) => s.locks[data.id]);
  const myUserId = useCollaborationStore((s) => s.myUserId);
  const isEditingMine = useCollaborationStore((s) => s.editingTargetId === data.id);
  const actions = useCollaborationActions();

  const [draftName, setDraftName] = useState(data.name);
  const inputRef = useRef<HTMLInputElement>(null);

  // Heartbeat cada 2000ms mientras el input sigue abierto (§8.2 punto 4):
  // si se corta (pestaña en segundo plano, hipo de red) el servidor vence
  // el lock a los 5s y LOCK_RELEASED nos avisa (ver useCollaboration).
  useEffect(() => {
    if (!isEditingMine || !actions) return;
    setDraftName(data.name);
    const interval = setInterval(() => actions.renewLock(data.id), 2000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo al abrir, no en cada tecla
  }, [isEditingMine, data.id, actions]);

  useEffect(() => {
    if (isEditingMine) inputRef.current?.select();
  }, [isEditingMine]);

  const lockedByOther = Boolean(lock && lock.userId !== myUserId);

  function handleEditClick(event: MouseEvent) {
    event.stopPropagation();
    // Se manda el ACQUIRE aunque ya se vea tomado por otro: la unica
    // autoridad real es el servidor (§8.2), no la ultima difusion que
    // llego. Si esta tomado, vuelve LOCK_DENIED y se muestra el toast — es
    // la demostracion del criterio del dia, no un atajo de UI.
    actions?.requestLock(data.id);
  }

  function commitRename() {
    const trimmed = draftName.trim();
    if (trimmed && trimmed !== data.name) {
      actions?.renameClass(data.id, trimmed);
    } else {
      actions?.releaseLock(data.id);
    }
  }

  function handleInputKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") {
      event.preventDefault();
      commitRename();
    } else if (event.key === "Escape") {
      event.preventDefault();
      setDraftName(data.name);
      actions?.releaseLock(data.id);
    }
  }

  const nameStyle = lock ? { borderBottom: `3px solid ${lock.color}` } : undefined;

  return (
    <div
      className="uml-class-node"
      style={
        selected
          ? { borderColor: "#3b5bdb", borderWidth: 2 }
          : lock
            ? { borderColor: lock.color, borderWidth: 2 }
            : undefined
      }
    >
      <Handle type="target" position={Position.Top} className="uml-class-node__handle" />
      <Handle type="source" position={Position.Bottom} className="uml-class-node__handle" />
      <div
        className={
          data.isAbstract
            ? "uml-class-node__name uml-class-node__name--abstract"
            : "uml-class-node__name"
        }
        style={nameStyle}
        title={lock ? `Editado por ${lock.userId === myUserId ? "vos" : lock.userName}` : undefined}
      >
        {isEditingMine ? (
          <input
            ref={inputRef}
            className="uml-class-node__name-input nodrag"
            value={draftName}
            onChange={(e) => setDraftName(e.target.value)}
            onBlur={commitRename}
            onKeyDown={handleInputKeyDown}
          />
        ) : (
          <span className="uml-class-node__name-row">
            <span className="uml-class-node__name-text">{data.name}</span>
            {lock && (
              <span className="uml-class-node__lock-icon" style={{ color: lock.color }}>
                🔒
              </span>
            )}
            <button
              type="button"
              className="uml-class-node__edit-btn nodrag"
              onClick={handleEditClick}
              title={lockedByOther ? `Editado por ${lock!.userName}` : "Renombrar"}
              data-locked={lockedByOther || undefined}
            >
              ✎
            </button>
          </span>
        )}
      </div>

      <div className="uml-class-node__section">
        {data.attributes.length === 0 ? (
          <div className="uml-class-node__empty">(sin atributos)</div>
        ) : (
          data.attributes.map((attr) => (
            <div
              key={attr.id}
              className={
                attr.isPrimaryKey
                  ? "uml-class-node__row uml-class-node__row--pk"
                  : "uml-class-node__row"
              }
            >
              {visibilitySymbol(attr.visibility)} {attr.name}: {attr.type}
            </div>
          ))
        )}
      </div>

      <div className="uml-class-node__section">
        {data.methods.length === 0 ? (
          <div className="uml-class-node__empty">(sin metodos)</div>
        ) : (
          data.methods.map((method) => (
            <div key={method.id} className="uml-class-node__row">
              {methodSignature(method)}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
