import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent } from "react";
import { Handle, Position, type NodeProps } from "reactflow";
import type { Attribute, ClassEntity } from "../types/canonical";
import { visibilitySymbol, methodSignature } from "../lib/uml";
import { useCollaborationActions } from "../hooks/collaborationActionsContext";
import { useCollaborationStore } from "../store/collaborationStore";
import { useOfflineStore } from "../store/offlineStore";
import { AttributeFormModal } from "./AttributeFormModal";
import "./UmlClassNode.css";

type Pending =
  | { kind: "rename" }
  | { kind: "add-attribute" }
  | { kind: "edit-attribute"; attribute: Attribute }
  | { kind: "delete-attribute"; attribute: Attribute }
  | { kind: "delete-class" }
  | null;

function lockTargetOf(pending: Pending, classId: string): string | null {
  if (!pending) return null;
  if (pending.kind === "edit-attribute" || pending.kind === "delete-attribute") return pending.attribute.id;
  return classId;
}

/**
 * Nodo React Flow que dibuja una clase UML con su notacion estandar: tres
 * compartimentos (nombre / atributos / metodos), nombre en cursiva si es
 * abstracta, simbolos de visibilidad y atributos PK subrayados. El nombre y
 * los atributos se editan bajo soft-lock (§8.2): se pide el candado, se
 * espera la confirmacion (LOCK_ACQUIRED con mi propio userId) y recien ahi
 * se muestra el formulario — nunca optimista, para no dejar escribir a
 * alguien que en realidad no puede.
 *
 * Los Handle son visibles y arrastrables: de acá sale una conexion nueva
 * (ADD_RELATIONSHIP, ver App.tsx `onConnect`). Top=target, bottom=source,
 * la convencion mas simple de React Flow — conectar de abajo de una clase
 * hacia arriba de otra.
 */
export function UmlClassNode({ data, selected }: NodeProps<ClassEntity>) {
  const lock = useCollaborationStore((s) => s.locks[data.id]);
  const myUserId = useCollaborationStore((s) => s.myUserId);
  const editingTargetId = useCollaborationStore((s) => s.editingTargetId);
  const isOffline = useOfflineStore((s) => s.state === "OFFLINE");
  const actions = useCollaborationActions();

  const [pending, setPending] = useState<Pending>(null);
  const [draftName, setDraftName] = useState(data.name);
  const inputRef = useRef<HTMLInputElement>(null);

  const myTargetId = lockTargetOf(pending, data.id);
  // Sin conexion se omite la fase de adquisicion de candado por completo
  // (§8.4): no tiene sentido esperar una confirmacion que nunca va a
  // llegar. La exclusion mutua no desaparece, se traslada al momento del
  // rebase — ver docs/dias/dia-08_2026-09-10.md punto 4.
  const confirmed = myTargetId !== null && (isOffline || editingTargetId === myTargetId);

  // Heartbeat cada 2000ms mientras haya un lock confirmado abierto (input de
  // rename o panel de atributo): si se corta (pestaña en 2do plano, hipo de
  // red) el servidor vence el lock a los 5s y LOCK_RELEASED nos avisa (ver
  // useCollaboration). Sin conexion no hay lock que renovar.
  useEffect(() => {
    if (!confirmed || !myTargetId || !actions || isOffline) return;
    const interval = setInterval(() => actions.renewLock(myTargetId), 2000);
    return () => clearInterval(interval);
  }, [confirmed, myTargetId, actions, isOffline]);

  useEffect(() => {
    if (pending?.kind === "rename" && confirmed) {
      setDraftName(data.name);
      inputRef.current?.select();
    }
  }, [pending, confirmed, data.name]);

  // Los borrados no abren un panel: piden el lock, y al confirmarse
  // preguntan con window.confirm y listo. Misma disciplina de "no
  // optimista" — la pregunta no aparece hasta que el candado es de verdad
  // nuestro.
  useEffect(() => {
    if (!confirmed || !pending) return;
    if (pending.kind === "delete-attribute") {
      const ok = window.confirm(`¿Borrar el atributo "${pending.attribute.name}"?`);
      if (ok) actions?.deleteAttribute(pending.attribute.id);
      else actions?.releaseLock(pending.attribute.id);
      setPending(null);
    } else if (pending.kind === "delete-class") {
      const ok = window.confirm(`¿Borrar la clase "${data.name}" y sus relaciones?`);
      if (ok) actions?.deleteClass(data.id);
      else actions?.releaseLock(data.id);
      setPending(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo cuando se confirma el lock, no en cada render
  }, [confirmed]);

  const lockedByOther = Boolean(lock && lock.userId !== myUserId);

  function start(next: Pending, targetId: string) {
    setPending(next);
    if (!isOffline) actions?.requestLock(targetId);
  }

  function cancelPending() {
    if (myTargetId) actions?.releaseLock(myTargetId);
    setPending(null);
  }

  function commitRename() {
    const trimmed = draftName.trim();
    if (trimmed && trimmed !== data.name) {
      actions?.renameClass(data.id, trimmed);
    } else {
      actions?.releaseLock(data.id);
    }
    setPending(null);
  }

  function handleInputKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") {
      event.preventDefault();
      commitRename();
    } else if (event.key === "Escape") {
      event.preventDefault();
      setDraftName(data.name);
      cancelPending();
    }
  }

  function stop(event: MouseEvent) {
    event.stopPropagation();
  }

  const nameStyle = lock ? { borderBottom: `3px solid ${lock.color}` } : undefined;
  const isEditingRename = pending?.kind === "rename" && confirmed;

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
        {isEditingRename ? (
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
              onClick={(e) => {
                stop(e);
                start({ kind: "rename" }, data.id);
              }}
              title={lockedByOther ? `Editado por ${lock!.userName}` : "Renombrar"}
              data-locked={lockedByOther || undefined}
            >
              ✎
            </button>
            <button
              type="button"
              className="uml-class-node__edit-btn uml-class-node__edit-btn--danger nodrag"
              onClick={(e) => {
                stop(e);
                start({ kind: "delete-class" }, data.id);
              }}
              title="Borrar clase"
              data-locked={lockedByOther || undefined}
            >
              🗑
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
              <span className="uml-class-node__attr-text">
                {visibilitySymbol(attr.visibility)} {attr.name}: {attr.type}
              </span>
              <span className="uml-class-node__attr-actions nodrag">
                <button
                  type="button"
                  className="uml-class-node__mini-btn"
                  onClick={() => start({ kind: "edit-attribute", attribute: attr }, attr.id)}
                  title="Editar atributo"
                >
                  ✎
                </button>
                <button
                  type="button"
                  className="uml-class-node__mini-btn"
                  onClick={() => start({ kind: "delete-attribute", attribute: attr }, attr.id)}
                  title="Borrar atributo"
                >
                  🗑
                </button>
              </span>
            </div>
          ))
        )}
        <button
          type="button"
          className="uml-class-node__add-attr nodrag"
          onClick={(e) => {
            stop(e);
            start({ kind: "add-attribute" }, data.id);
          }}
        >
          + atributo
        </button>
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

      {pending?.kind === "add-attribute" && confirmed && (
        <AttributeFormModal
          onSubmit={(attribute) => {
            actions?.addAttribute(data.id, attribute);
            setPending(null);
          }}
          onClose={cancelPending}
        />
      )}
      {pending?.kind === "edit-attribute" && confirmed && (
        <AttributeFormModal
          initial={pending.attribute}
          onSubmit={(attribute) => {
            actions?.updateAttribute(attribute);
            setPending(null);
          }}
          onClose={cancelPending}
        />
      )}
    </div>
  );
}
