import { useState, type FormEvent } from "react";
import { Modal } from "./Modal";
import type { Attribute, AttributeType, Visibility } from "../types/canonical";

const TYPES: AttributeType[] = [
  "INTEGER",
  "BIGINT",
  "VARCHAR",
  "TEXT",
  "DECIMAL",
  "BOOLEAN",
  "DATE",
  "DATETIME",
  "UUID",
];
const VISIBILITIES: Visibility[] = ["PUBLIC", "PACKAGE", "PROTECTED", "PRIVATE"];

interface Props {
  /** Si viene, edita ese atributo (ya existe); si no, crea uno nuevo. */
  initial?: Attribute;
  onSubmit: (attribute: Attribute) => void;
  onClose: () => void;
}

export function AttributeFormModal({ initial, onSubmit, onClose }: Props) {
  const [name, setName] = useState(initial?.name ?? "");
  const [type, setType] = useState<AttributeType>(initial?.type ?? "VARCHAR");
  const [visibility, setVisibility] = useState<Visibility>(initial?.visibility ?? "PRIVATE");
  const [isPrimaryKey, setIsPrimaryKey] = useState(initial?.isPrimaryKey ?? false);
  const [isNullable, setIsNullable] = useState(initial?.isNullable ?? true);
  const [isUnique, setIsUnique] = useState(initial?.isUnique ?? false);
  const [length, setLength] = useState(initial?.length ?? 255);
  const [precision, setPrecision] = useState(initial?.precision ?? 10);
  const [scale, setScale] = useState(initial?.scale ?? 2);
  const [defaultValue, setDefaultValue] = useState(initial?.defaultValue ?? "");

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    onSubmit({
      id: initial?.id ?? crypto.randomUUID(),
      name: trimmed,
      type,
      visibility,
      isPrimaryKey,
      isNullable,
      isUnique,
      length: type === "VARCHAR" || type === "TEXT" ? length : undefined,
      precision: type === "DECIMAL" ? precision : undefined,
      scale: type === "DECIMAL" ? scale : undefined,
      defaultValue: defaultValue.trim() ? defaultValue.trim() : null,
    });
  }

  return (
    <Modal title={initial ? "Editar atributo" : "Nuevo atributo"} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="attr-name">Nombre</label>
          <input id="attr-name" type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus required />
        </div>

        <div className="modal__field">
          <label htmlFor="attr-type">Tipo</label>
          <select id="attr-type" value={type} onChange={(e) => setType(e.target.value as AttributeType)}>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>

        <div className="modal__field">
          <label htmlFor="attr-visibility">Visibilidad</label>
          <select
            id="attr-visibility"
            value={visibility}
            onChange={(e) => setVisibility(e.target.value as Visibility)}
          >
            {VISIBILITIES.map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
          </select>
        </div>

        {(type === "VARCHAR" || type === "TEXT") && (
          <div className="modal__field">
            <label htmlFor="attr-length">Longitud</label>
            <input
              id="attr-length"
              type="number"
              value={length}
              onChange={(e) => setLength(Number(e.target.value))}
              min={1}
            />
          </div>
        )}

        {type === "DECIMAL" && (
          <>
            <div className="modal__field">
              <label htmlFor="attr-precision">Precisión</label>
              <input
                id="attr-precision"
                type="number"
                value={precision}
                onChange={(e) => setPrecision(Number(e.target.value))}
                min={1}
              />
            </div>
            <div className="modal__field">
              <label htmlFor="attr-scale">Escala</label>
              <input
                id="attr-scale"
                type="number"
                value={scale}
                onChange={(e) => setScale(Number(e.target.value))}
                min={0}
              />
            </div>
          </>
        )}

        <div className="modal__field">
          <label htmlFor="attr-default">Valor por defecto (opcional)</label>
          <input
            id="attr-default"
            type="text"
            value={defaultValue}
            onChange={(e) => setDefaultValue(e.target.value)}
          />
        </div>

        <div className="modal__checkboxes">
          <label>
            <input type="checkbox" checked={isPrimaryKey} onChange={(e) => setIsPrimaryKey(e.target.checked)} />
            Clave primaria
          </label>
          <label>
            <input type="checkbox" checked={isNullable} onChange={(e) => setIsNullable(e.target.checked)} />
            Nulable
          </label>
          <label>
            <input type="checkbox" checked={isUnique} onChange={(e) => setIsUnique(e.target.checked)} />
            Único
          </label>
        </div>

        <div className="modal__actions">
          <button type="button" onClick={onClose}>
            Cancelar
          </button>
          <button type="submit" className="modal__primary">
            {initial ? "Guardar" : "Agregar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
