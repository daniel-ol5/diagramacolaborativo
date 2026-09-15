import { useState, type FormEvent } from "react";
import { Modal } from "../components/Modal";

interface Props {
  onCreate: (name: string) => void;
  onClose: () => void;
}

export function NewDiagramModal({ onCreate, onClose }: Props) {
  const [name, setName] = useState("");

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed) onCreate(trimmed);
  }

  return (
    <Modal title="Nuevo diagrama" onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="new-diagram-name">Nombre</label>
          <input
            id="new-diagram-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
            required
          />
        </div>
        <div className="modal__actions">
          <button type="button" onClick={onClose}>
            Cancelar
          </button>
          <button type="submit" className="modal__primary">
            Crear
          </button>
        </div>
      </form>
    </Modal>
  );
}
