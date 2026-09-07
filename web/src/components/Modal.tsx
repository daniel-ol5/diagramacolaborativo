import type { ReactNode } from "react";
import "./Modal.css";

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
}

/** Modal generico y chico: overlay + caja. Lo reusan el alta de clase, el panel de atributos y la creacion de relaciones. */
export function Modal({ title, onClose, children }: Props) {
  return (
    <div className="modal__overlay" onMouseDown={onClose}>
      <div className="modal__box" onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal__header">
          <h2>{title}</h2>
          <button type="button" className="modal__close" onClick={onClose} aria-label="Cerrar">
            ✕
          </button>
        </div>
        <div className="modal__body">{children}</div>
      </div>
    </div>
  );
}
