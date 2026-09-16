import { useState, type FormEvent } from "react";
import { Modal } from "../components/Modal";

interface Props {
  onGenerate: (basePackage: string) => void;
  onClose: () => void;
  generating: boolean;
}

/** Pide el paquete base antes de generar -- "no lo inventes", se lo pregunta al usuario. */
export function GenerateBackendModal({ onGenerate, onClose, generating }: Props) {
  const [basePackage, setBasePackage] = useState("com.empresa.app");

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = basePackage.trim();
    if (trimmed) onGenerate(trimmed);
  }

  return (
    <Modal title="Generar backend" onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="generate-base-package">Paquete base</label>
          <input
            id="generate-base-package"
            type="text"
            value={basePackage}
            onChange={(e) => setBasePackage(e.target.value)}
            pattern="[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*"
            autoFocus
            required
          />
        </div>
        <div className="modal__actions">
          <button type="button" onClick={onClose} disabled={generating}>
            Cancelar
          </button>
          <button type="submit" className="modal__primary" disabled={generating}>
            {generating ? "Generando…" : "Generar"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
