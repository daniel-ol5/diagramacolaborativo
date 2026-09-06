import { Handle, Position, type NodeProps } from "reactflow";
import type { ClassEntity } from "../types/canonical";
import { visibilitySymbol, methodSignature } from "../lib/uml";
import "./UmlClassNode.css";

/**
 * Nodo React Flow que dibuja una clase UML con su notacion estandar: tres
 * compartimentos (nombre / atributos / metodos), nombre en cursiva si es
 * abstracta, simbolos de visibilidad y atributos PK subrayados.
 *
 * Los Handle son invisibles y no se usan para su posicion: React Flow se
 * niega a crear una arista si el nodo no tiene al menos un handle "source" y
 * uno "target" (advertencia #008), aunque el path lo calcule por su cuenta
 * `UmlRelationshipEdge` a partir de `position`/`width`/`height` del
 * canonico. Crear conexiones arrastrando es del Dia 8, no de hoy.
 */
export function UmlClassNode({ data, selected }: NodeProps<ClassEntity>) {
  return (
    <div
      className="uml-class-node"
      style={selected ? { borderColor: "#3b5bdb", borderWidth: 2 } : undefined}
    >
      <Handle type="target" position={Position.Top} className="uml-class-node__handle" />
      <Handle type="source" position={Position.Bottom} className="uml-class-node__handle" />
      <div
        className={
          data.isAbstract
            ? "uml-class-node__name uml-class-node__name--abstract"
            : "uml-class-node__name"
        }
      >
        {data.name}
      </div>

      <div className="uml-class-node__section">
        {data.attributes.length === 0 ? (
          <div className="uml-class-node__empty">(sin atributos)</div>
        ) : (
          data.attributes.map((attr) => (
            <div
              key={attr.id}
              className={
                attr.isPrimaryKey
                  ? "uml-class-node__row uml-class-node__row--pk"
                  : "uml-class-node__row"
              }
            >
              {visibilitySymbol(attr.visibility)} {attr.name}: {attr.type}
            </div>
          ))
        )}
      </div>

      <div className="uml-class-node__section">
        {data.methods.length === 0 ? (
          <div className="uml-class-node__empty">(sin metodos)</div>
        ) : (
          data.methods.map((method) => (
            <div key={method.id} className="uml-class-node__row">
              {methodSignature(method)}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
