import { useState, type FormEvent } from "react";
import { Modal } from "./Modal";
import type { Multiplicity, OwningSide, Relationship, RelationshipType } from "../types/canonical";
import { deriveOwningSide } from "../lib/relationship";
import { relationshipTypeLabel } from "../lib/uml";

const TYPES: RelationshipType[] = [
  "ASSOCIATION",
  "AGGREGATION",
  "COMPOSITION",
  "MANY_TO_MANY",
  "INHERITANCE",
  "DEPENDENCY",
];
const MULTIPLICITIES: Multiplicity[] = ["0..1", "1..1", "0..*", "1..*"];

const LLEVA_MULTIPLICIDAD = (t: string) => t !== "INHERITANCE" && t !== "DEPENDENCY" && t !== "ASSOCIATION_CLASS";

interface Props {
  sourceId: string;
  sourceName: string;
  targetId: string;
  targetName: string;
  initialType?: RelationshipType | "ASSOCIATION_CLASS";
  onSubmit: (relationship: Relationship) => void;
  onCreateAssociationClass?: (className: string) => void;
  onClose: () => void;
}

export function RelationshipFormModal({
  sourceId,
  sourceName,
  targetId,
  targetName,
  initialType = "ASSOCIATION",
  onSubmit,
  onCreateAssociationClass,
  onClose,
}: Props) {
  const [type, setType] = useState<RelationshipType | "ASSOCIATION_CLASS">(initialType);
  const [sourceMultiplicity, setSourceMultiplicity] = useState<Multiplicity>(
    initialType === "MANY_TO_MANY" ? "0..*" : "1..1",
  );
  const [targetMultiplicity, setTargetMultiplicity] = useState<Multiplicity>("0..*");
  const [sourceRole, setSourceRole] = useState("");
  const [targetRole, setTargetRole] = useState("");
  const [joinTableName, setJoinTableName] = useState("");
  const [createAsClassOnCanvas, setCreateAsClassOnCanvas] = useState(false);
  const [associationClassName, setAssociationClassName] = useState("");
  const [owningSide, setOwningSide] = useState<OwningSide>(() => {
    const baseType = initialType === "ASSOCIATION_CLASS" ? "ASSOCIATION" : initialType;
    return deriveOwningSide(baseType, "1..1", "0..*") ?? "SOURCE";
  });
  const [owningSideTouched, setOwningSideTouched] = useState(false);

  const derivada = type !== "ASSOCIATION_CLASS"
    ? deriveOwningSide(type, sourceMultiplicity, targetMultiplicity)
    : null;
  const owningSideActual = owningSideTouched ? owningSide : (derivada ?? owningSide);

  function actualizarMultiplicidad(setter: (m: Multiplicity) => void, valor: Multiplicity) {
    setter(valor);
    if (!owningSideTouched && type !== "ASSOCIATION_CLASS") {
      const nueva = deriveOwningSide(type, setter === setSourceMultiplicity ? valor : sourceMultiplicity, setter === setTargetMultiplicity ? valor : targetMultiplicity);
      if (nueva) setOwningSide(nueva);
    }
  }

  function handleTypeChange(nuevo: RelationshipType | "ASSOCIATION_CLASS") {
    setType(nuevo);
    if (nuevo !== "ASSOCIATION_CLASS" && !owningSideTouched) {
      const nueva = deriveOwningSide(nuevo, sourceMultiplicity, targetMultiplicity);
      if (nueva) setOwningSide(nueva);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();

    if (type === "ASSOCIATION_CLASS") {
      const name = associationClassName.trim();
      if (!name) return;
      onCreateAssociationClass?.(name);
      return;
    }

    if (type === "MANY_TO_MANY" && createAsClassOnCanvas && joinTableName.trim()) {
      onCreateAssociationClass?.(joinTableName.trim());
      return;
    }

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

  const isAssocClass = type === "ASSOCIATION_CLASS" || (type === "MANY_TO_MANY" && createAsClassOnCanvas && Boolean(joinTableName.trim()));

  return (
    <Modal title={`Nueva relación: ${sourceName} → ${targetName}`} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="rel-type">Tipo</label>
          <select
            id="rel-type"
            value={type}
            onChange={(e) => handleTypeChange(e.target.value as RelationshipType | "ASSOCIATION_CLASS")}
          >
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {relationshipTypeLabel(t)}
              </option>
            ))}
            <option value="ASSOCIATION_CLASS">Clase asociación (crea clase intermedia en lienzo)</option>
          </select>
        </div>

        {type === "ASSOCIATION_CLASS" && (
          <div className="modal__field">
            <label htmlFor="rel-assoc-class">Nombre de la clase intermedia</label>
            <input
              id="rel-assoc-class"
              type="text"
              placeholder="Ej: Inscripcion, AS..."
              value={associationClassName}
              onChange={(e) => setAssociationClassName(e.target.value)}
              required
              autoFocus
            />
            <small style={{ color: "var(--color-text-secondary, #666)", display: "block", marginTop: "4px" }}>
              Se creará un recuadro de clase en el lienzo entre <strong>{sourceName}</strong> y <strong>{targetName}</strong>, con relaciones de asociación (1..1 → 0..*).
            </small>
          </div>
        )}

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
              <>
                <div className="modal__field">
                  <label htmlFor="rel-join">Nombre de tabla intermedia (opcional)</label>
                  <input
                    id="rel-join"
                    type="text"
                    value={joinTableName}
                    onChange={(e) => setJoinTableName(e.target.value)}
                    placeholder="Ej: AS"
                  />
                </div>
                <div className="modal__field" style={{ marginTop: "-4px", marginBottom: "12px" }}>
                  <label style={{ display: "flex", alignItems: "center", gap: "8px", cursor: "pointer", fontSize: "0.88rem" }}>
                    <input
                      type="checkbox"
                      checked={createAsClassOnCanvas}
                      onChange={(e) => setCreateAsClassOnCanvas(e.target.checked)}
                      disabled={!joinTableName.trim()}
                    />
                    <span>Crear también como clase intermedia en el lienzo (Clase Asociación)</span>
                  </label>
                </div>
              </>
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
            {isAssocClass ? "Crear clase asociación" : "Crear relación"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
