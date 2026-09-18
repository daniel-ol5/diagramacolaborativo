import type { NodeProps } from "reactflow";
import { Handle, Position } from "reactflow";
import type { ErTableData } from "./erView";
import "./ErTableNode.css";

function keyBadge(pk: boolean, fk: boolean): string {
  if (pk && fk) return "PK,FK"; // herencia JOINED: el id del hijo es clave primaria Y foranea al padre a la vez
  if (pk) return "PK";
  if (fk) return "FK";
  return "";
}

/**
 * CU15, vista ER: una tabla, no una clase. Sin altura fija (a diferencia de
 * UmlClassNode) -- el numero de columnas de una tabla ER varia con sus FK
 * sintetizadas, y forzar una altura aca repetiria el defecto conocido de la
 * caja UML en un lugar donde todavia no existe.
 */
export function ErTableNode({ data }: NodeProps<ErTableData>) {
  return (
    <div className={`er-table${data.isJunction ? " er-table--junction" : ""}`}>
      <Handle type="target" position={Position.Top} style={{ visibility: "hidden" }} />
      <div className="er-table__name" title={data.isAbstract ? "Tiene tabla propia, pero sin API REST (superclase abstracta)" : undefined}>
        {data.tableName}
        {data.isAbstract && <span className="er-table__abstract-mark"> (sin API)</span>}
      </div>
      {data.columns.length === 0 && <div className="er-table__empty">(sin columnas)</div>}
      {data.columns.map((col, i) => (
        <div key={i} className={`er-table__row${col.isPrimaryKey ? " er-table__row--pk" : ""}`}>
          <span className="er-table__key-badge">{keyBadge(col.isPrimaryKey, col.isForeignKey)}</span>
          <span className="er-table__col-name">{col.quoted ? `"${col.name}"` : col.name}</span>
          <span className="er-table__col-type">{col.type}</span>
        </div>
      ))}
      <Handle type="source" position={Position.Bottom} style={{ visibility: "hidden" }} />
    </div>
  );
}
