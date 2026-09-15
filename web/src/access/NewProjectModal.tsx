import { useState, type FormEvent } from "react";
import { Modal } from "../components/Modal";

interface Props {
  onCreate: (name: string, description: string) => void;
  onClose: () => void;
}

export function NewProjectModal({ onCreate, onClose }: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed) onCreate(trimmed, description.trim());
  }

  return (
    <Modal title="Nuevo proyecto" onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal__field">
          <label htmlFor="new-project-name">Nombre</label>
          <input
            id="new-project-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
            required
          />
        </div>
        <div className="modal__field">
          <label htmlFor="new-project-description">Descripción (opcional)</label>
          <input
            id="new-project-description"
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
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
