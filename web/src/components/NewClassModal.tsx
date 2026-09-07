import { useState, type FormEvent } from "react";
import { Modal } from "./Modal";

interface Props {
  onCreate: (name: string) => void;
  onClose: () => void;
}

export function NewClassModal({ onCreate, onClose }: Props) {
  const [name, setName] = useState("");

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed) onCreate(trimmed);
  }

  return (
    <Modal title="Nueva clase" onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="new-class-name">Nombre</label>
          <input
            id="new-class-name"
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
