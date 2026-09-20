import { useEffect, useState } from "react";
import type { CanonicalModel, Package } from "../types/canonical";
import { useCollaborationStore } from "../store/collaborationStore";
import { useOfflineStore } from "../store/offlineStore";
import "./PackagePanel.css";

interface Actions {
  requestLock: (targetId: string) => void;
  releaseLock: (targetId: string) => void;
  renewLock: (targetId: string) => void;
  addPackage: (paquete: Package) => void;
  updatePackage: (paquete: Package) => void;
  deletePackage: (packageId: string) => void;
}

interface Props {
  model: CanonicalModel;
  actions: Actions;
  onClose: () => void;
}

type Pending = { kind: "edit"; pkg: Package } | { kind: "delete"; pkg: Package } | { kind: "new" } | null;

/**
 * Paquetes: alta/edicion/baja y pertenencia de clases. Mismo mecanismo de
 * soft-lock que atributos/metodos (§8.2) -- editar o borrar pide el
 * candado sobre package.id y espera la confirmacion antes de mostrar el
 * formulario; ALTA no pide candado (igual que ADD_CLASS/ADD_RELATIONSHIP:
 * el paquete todavia no existe, no hay nada que dos personas puedan
 * pisarse).
 */
export function PackagePanel({ model, actions, onClose }: Props) {
  const locks = useCollaborationStore((s) => s.locks);
  const myUserId = useCollaborationStore((s) => s.myUserId);
  const editingTargetId = useCollaborationStore((s) => s.editingTargetId);
  const isOffline = useOfflineStore((s) => s.state === "OFFLINE");

  const [pending, setPending] = useState<Pending>(null);
  const [draftName, setDraftName] = useState("");
  const [draftClassIds, setDraftClassIds] = useState<Set<string>>(new Set());

  const paquetes = model.packages ?? [];
  const targetId = pending?.kind === "edit" || pending?.kind === "delete" ? pending.pkg.id : null;
  const confirmed = targetId !== null && (isOffline || editingTargetId === targetId);

  useEffect(() => {
    if (!confirmed || !targetId || isOffline) return;
    const interval = setInterval(() => actions.renewLock(targetId), 2000);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- actions es un objeto nuevo cada render, no forma parte de la identidad del efecto
  }, [confirmed, targetId, isOffline]);

  useEffect(() => {
    if (!confirmed || pending?.kind !== "edit") return;
    setDraftName(pending.pkg.name);
    setDraftClassIds(new Set(pending.pkg.classIds));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo al confirmarse el lock, no en cada tecla
  }, [confirmed]);

  useEffect(() => {
    if (!confirmed || pending?.kind !== "delete") return;
    const ok = window.confirm(`¿Borrar el paquete "${pending.pkg.name}"?`);
    if (ok) actions.deletePackage(pending.pkg.id);
    else actions.releaseLock(pending.pkg.id);
    setPending(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [confirmed]);

  function empezarEdicion(pkg: Package) {
    setPending({ kind: "edit", pkg });
    if (!isOffline) actions.requestLock(pkg.id);
  }

  function empezarBorrado(pkg: Package) {
    setPending({ kind: "delete", pkg });
    if (!isOffline) actions.requestLock(pkg.id);
  }

  function cancelarEdicion() {
    if (targetId) actions.releaseLock(targetId);
    setPending(null);
  }

  function guardarEdicion() {
    if (pending?.kind !== "edit") return;
    const trimmed = draftName.trim();
    if (trimmed) {
      actions.updatePackage({ ...pending.pkg, name: trimmed, classIds: [...draftClassIds] });
    } else {
      actions.releaseLock(pending.pkg.id);
    }
    setPending(null);
  }

  function alternarClase(classId: string) {
    setDraftClassIds((prev) => {
      const next = new Set(prev);
      if (next.has(classId)) next.delete(classId);
      else next.add(classId);
      return next;
    });
  }

  function crear(nombre: string) {
    const trimmed = nombre.trim();
    if (!trimmed) return;
    actions.addPackage({ id: crypto.randomUUID(), name: trimmed, classIds: [] });
    setPending(null);
  }

  return (
    <div className="package-panel">
      <div className="package-panel__header">
        <span>Paquetes</span>
        <button type="button" className="package-panel__close" onClick={onClose} aria-label="Cerrar">
          ✕
        </button>
      </div>
      <div className="package-panel__list">
        {paquetes.length === 0 && pending?.kind !== "new" && (
          <div className="package-panel__empty">Sin paquetes todavía.</div>
        )}
        {paquetes.map((pkg) => {
          const lock = locks[pkg.id];
          const lockedByOther = Boolean(lock && lock.userId !== myUserId);
          const isEditingThis = pending?.kind === "edit" && pending.pkg.id === pkg.id && confirmed;

          if (isEditingThis) {
            return (
              <div key={pkg.id} className="package-panel__item package-panel__item--editing">
                <input
                  className="package-panel__name-input"
                  value={draftName}
                  onChange={(e) => setDraftName(e.target.value)}
                  autoFocus
                />
                <div className="package-panel__class-list">
                  {model.classes.map((c) => (
                    <label key={c.id} className="package-panel__class-row">
                      <input
                        type="checkbox"
                        checked={draftClassIds.has(c.id)}
                        onChange={() => alternarClase(c.id)}
                      />
                      {c.name}
                    </label>
                  ))}
                </div>
                <div className="package-panel__actions">
                  <button type="button" onClick={cancelarEdicion}>
                    Cancelar
                  </button>
                  <button type="button" className="package-panel__primary" onClick={guardarEdicion}>
                    Guardar
                  </button>
                </div>
              </div>
            );
          }

          return (
            <div key={pkg.id} className="package-panel__item">
              <span className="package-panel__name" title={lock ? `Editado por ${lock.userId === myUserId ? "vos" : lock.userName}` : undefined}>
                {pkg.name}
                {lock && <span className="package-panel__lock-icon" style={{ color: lock.color }}>🔒</span>}
              </span>
              <span className="package-panel__count">{pkg.classIds.length} clase(s)</span>
              <span className="package-panel__row-actions">
                <button type="button" onClick={() => empezarEdicion(pkg)} title={lockedByOther ? `Editado por ${lock!.userName}` : "Editar"}>
                  ✎
                </button>
                <button type="button" onClick={() => empezarBorrado(pkg)} title="Borrar paquete">
                  🗑
                </button>
              </span>
            </div>
          );
        })}

        {pending?.kind === "new" ? (
          <NuevoPaquete onCrear={crear} onCancelar={() => setPending(null)} />
        ) : (
          <button type="button" className="package-panel__add" onClick={() => setPending({ kind: "new" })}>
            + Paquete
          </button>
        )}
      </div>
    </div>
  );
}

function NuevoPaquete({ onCrear, onCancelar }: { onCrear: (nombre: string) => void; onCancelar: () => void }) {
  const [nombre, setNombre] = useState("");
  return (
    <div className="package-panel__item package-panel__item--editing">
      <input
        className="package-panel__name-input"
        placeholder="Nombre del paquete"
        value={nombre}
        onChange={(e) => setNombre(e.target.value)}
        autoFocus
      />
      <div className="package-panel__actions">
        <button type="button" onClick={onCancelar}>
          Cancelar
        </button>
        <button type="button" className="package-panel__primary" onClick={() => onCrear(nombre)}>
          Crear
        </button>
      </div>
    </div>
  );
}
