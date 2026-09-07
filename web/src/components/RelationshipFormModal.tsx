import { useState, type FormEvent } from "react";
import { Modal } from "./Modal";
import type { Multiplicity, OwningSide, Relationship, RelationshipType } from "../types/canonical";
import { deriveOwningSide } from "../lib/relationship";

const TYPES: RelationshipType[] = [
  "ASSOCIATION",
  "AGGREGATION",
  "COMPOSITION",
  "MANY_TO_MANY",
  "INHERITANCE",
  "DEPENDENCY",
];
const MULTIPLICITIES: Multiplicity[] = ["0..1", "1..1", "0..*", "1..*"];

const LLEVA_MULTIPLICIDAD = (t: RelationshipType) => t !== "INHERITANCE" && t !== "DEPENDENCY";

interface Props {
  sourceId: string;
  sourceName: string;
  targetId: string;
  targetName: string;
  onSubmit: (relationship: Relationship) => void;
  onClose: () => void;
}

export function RelationshipFormModal({ sourceId, sourceName, targetId, targetName, onSubmit, onClose }: Props) {
  const [type, setType] = useState<RelationshipType>("ASSOCIATION");
  const [sourceMultiplicity, setSourceMultiplicity] = useState<Multiplicity>("1..1");
  const [targetMultiplicity, setTargetMultiplicity] = useState<Multiplicity>("0..*");
  const [sourceRole, setSourceRole] = useState("");
  const [targetRole, setTargetRole] = useState("");
  const [joinTableName, setJoinTableName] = useState("");
  const [owningSide, setOwningSide] = useState<OwningSide>(
    () => deriveOwningSide("ASSOCIATION", "1..1", "0..*") ?? "SOURCE",
  );
  const [owningSideTouched, setOwningSideTouched] = useState(false);

  const derivada = deriveOwningSide(type, sourceMultiplicity, targetMultiplicity);
  const owningSideActual = owningSideTouched ? owningSide : (derivada ?? owningSide);

  function actualizarMultiplicidad(setter: (m: Multiplicity) => void, valor: Multiplicity) {
    setter(valor);
    if (!owningSideTouched) {
      const nueva = deriveOwningSide(type, setter === setSourceMultiplicity ? valor : sourceMultiplicity, setter === setTargetMultiplicity ? valor : targetMultiplicity);
      if (nueva) setOwningSide(nueva);
    }
  }

  function handleTypeChange(nuevo: RelationshipType) {
    setType(nuevo);
    if (!owningSideTouched) {
      const nueva = deriveOwningSide(nuevo, sourceMultiplicity, targetMultiplicity);
      if (nueva) setOwningSide(nueva);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const llevaMultiplicidad = LLEVA_MULTIPLICIDAD(type);
    onSubmit({
      id: crypto.randomUUID(),
      sourceClassId: sourceId,
      targetClassId: targetId,
      type,
      owningSide: llevaMultiplicidad ? owningSideActual : "SOURCE",
      joinTableName: type === "MANY_TO_MANY" && joinTableName.trim() ? joinTableName.trim() : undefined,
      sourceMultiplicity: llevaMultiplicidad ? sourceMultiplicity : undefined,
      targetMultiplicity: llevaMultiplicidad ? targetMultiplicity : undefined,
      sourceRole: sourceRole.trim() || undefined,
      targetRole: targetRole.trim() || undefined,
      isNavigable: true,
      waypoints: [],
    });
  }

  return (
    <Modal title={`Nueva relación: ${sourceName} → ${targetName}`} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="rel-type">Tipo</label>
          <select id="rel-type" value={type} onChange={(e) => handleTypeChange(e.target.value as RelationshipType)}>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>

        {LLEVA_MULTIPLICIDAD(type) && (
          <>
            <div className="modal__field">
              <label htmlFor="rel-source-mult">Multiplicidad de origen ({sourceName})</label>
              <select
                id="rel-source-mult"
                value={sourceMultiplicity}
                onChange={(e) => actualizarMultiplicidad(setSourceMultiplicity, e.target.value as Multiplicity)}
              >
                {MULTIPLICITIES.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            </div>
            <div className="modal__field">
              <label htmlFor="rel-target-mult">Multiplicidad de destino ({targetName})</label>
              <select
                id="rel-target-mult"
                value={targetMultiplicity}
                onChange={(e) => actualizarMultiplicidad(setTargetMultiplicity, e.target.value as Multiplicity)}
              >
                {MULTIPLICITIES.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
            </div>
            <div className="modal__field">
              <label htmlFor="rel-owning">
                Lado dueño (define la clave foránea){derivada && !owningSideTouched ? " — derivado" : ""}
              </label>
              <select
                id="rel-owning"
                value={owningSideActual}
                onChange={(e) => {
                  setOwningSide(e.target.value as OwningSide);
                  setOwningSideTouched(true);
                }}
              >
                <option value="SOURCE">{sourceName} (origen)</option>
                <option value="TARGET">{targetName} (destino)</option>
              </select>
            </div>
            {type === "MANY_TO_MANY" && (
              <div className="modal__field">
                <label htmlFor="rel-join">Nombre de tabla intermedia (opcional)</label>
                <input
                  id="rel-join"
                  type="text"
                  value={joinTableName}
                  onChange={(e) => setJoinTableName(e.target.value)}
                />
              </div>
            )}
            <div className="modal__field">
              <label htmlFor="rel-source-role">Rol de origen (opcional)</label>
              <input
                id="rel-source-role"
                type="text"
                value={sourceRole}
                onChange={(e) => setSourceRole(e.target.value)}
              />
            </div>
            <div className="modal__field">
              <label htmlFor="rel-target-role">Rol de destino (opcional)</label>
              <input
                id="rel-target-role"
                type="text"
                value={targetRole}
                onChange={(e) => setTargetRole(e.target.value)}
              />
            </div>
          </>
        )}

        <div className="modal__actions">
          <button type="button" onClick={onClose}>
            Cancelar
          </button>
          <button type="submit" className="modal__primary">
            Crear relación
          </button>
        </div>
      </form>
    </Modal>
  );
}
