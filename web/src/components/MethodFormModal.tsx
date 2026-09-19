import { useState, type FormEvent } from "react";
import { Modal } from "./Modal";
import type { Method, Parameter, Visibility } from "../types/canonical";

const VISIBILITIES: Visibility[] = ["PUBLIC", "PACKAGE", "PROTECTED", "PRIVATE"];

interface Props {
  /** Si viene, edita ese metodo (ya existe); si no, crea uno nuevo. */
  initial?: Method;
  onSubmit: (method: Method) => void;
  onClose: () => void;
}

/**
 * A diferencia de Attribute, el tipo de un parametro o del retorno no sale
 * de un enum fijo (AttributeType) -- un metodo puede devolver "void", el
 * nombre de otra clase del diagrama, o cualquier tipo del lenguaje de
 * destino. Por eso son texto libre, no un <select>, igual que ya declara
 * `Parameter.type`/`Method.returnType` en types/canonical.ts.
 */
export function MethodFormModal({ initial, onSubmit, onClose }: Props) {
  const [name, setName] = useState(initial?.name ?? "");
  const [returnType, setReturnType] = useState(initial?.returnType ?? "void");
  const [visibility, setVisibility] = useState<Visibility>(initial?.visibility ?? "PUBLIC");
  const [parameters, setParameters] = useState<Parameter[]>(initial?.parameters ?? []);

  function updateParameter(index: number, field: keyof Parameter, value: string) {
    setParameters((prev) => prev.map((p, i) => (i === index ? { ...p, [field]: value } : p)));
  }

  function removeParameter(index: number) {
    setParameters((prev) => prev.filter((_, i) => i !== index));
  }

  function addParameter() {
    setParameters((prev) => [...prev, { name: "", type: "String" }]);
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) return;
    const parametrosValidos = parameters
      .map((p) => ({ name: p.name.trim(), type: p.type.trim() }))
      .filter((p) => p.name && p.type);
    onSubmit({
      id: initial?.id ?? crypto.randomUUID(),
      name: trimmed,
      returnType: returnType.trim() || "void",
      visibility,
      parameters: parametrosValidos.length > 0 ? parametrosValidos : undefined,
    });
  }

  return (
    <Modal title={initial ? "Editar método" : "Nuevo método"} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="method-name">Nombre</label>
          <input id="method-name" type="text" value={name} onChange={(e) => setName(e.target.value)} autoFocus required />
        </div>

        <div className="modal__field">
          <label htmlFor="method-return-type">Tipo de retorno</label>
          <input
            id="method-return-type"
            type="text"
            value={returnType}
            onChange={(e) => setReturnType(e.target.value)}
            placeholder="void"
          />
        </div>

        <div className="modal__field">
          <label htmlFor="method-visibility">Visibilidad</label>
          <select
            id="method-visibility"
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

        <div className="modal__field">
          <label>Parámetros</label>
          {parameters.map((p, i) => (
            <div key={i} className="method-form__parameter-row">
              <input
                type="text"
                value={p.name}
                onChange={(e) => updateParameter(i, "name", e.target.value)}
                placeholder="nombre"
                aria-label={`Nombre del parámetro ${i + 1}`}
              />
              <input
                type="text"
                value={p.type}
                onChange={(e) => updateParameter(i, "type", e.target.value)}
                placeholder="tipo"
                aria-label={`Tipo del parámetro ${i + 1}`}
              />
              <button type="button" className="method-form__remove-param" onClick={() => removeParameter(i)} title="Quitar parámetro">
                ✕
              </button>
            </div>
          ))}
          <button type="button" className="method-form__add-param" onClick={addParameter}>
            + parámetro
          </button>
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
