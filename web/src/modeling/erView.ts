import type { CanonicalModel, ClassEntity } from "../types/canonical";
import type { SchemaPreview, SchemaTable } from "./schemaPreviewApi";
import { displayName } from "./schemaPreviewApi";

/**
 * CU15: la misma notacion UML o Entidad-Relacion sobre el MISMO modelo
 * canonico, sin convertir nada.
 *
 * La forma fisica de cada tabla (nombre, columnas, PK/FK ya resueltas del
 * lado correcto, herencia JOINED, tablas intermedias) sale ENTERA de
 * GET /api/v1/diagrams/{id}/schema-preview -- "vos dibujas, el servidor
 * calcula". Nada de eso se recalcula aca: ni snake_case, ni de que lado va
 * la FK, ni el nombre de una tabla intermedia. Lo unico que sigue
 * saliendo del canonico son las MULTIPLICIDADES de cada relacion (para la
 * pata de gallo), porque esas no son un calculo del generador -- son un
 * dato directo de `Relationship`, igual que ya lee UmlRelationshipEdge.
 */

export interface ErColumn {
  name: string;
  quoted: boolean;
  type: string;
  isPrimaryKey: boolean;
  /** Una columna puede ser PK Y FK a la vez (herencia JOINED: el id del hijo referencia al padre) -- no excluyente con isPrimaryKey. */
  isForeignKey: boolean;
}

export interface ErTableData {
  tableName: string;
  quoted: boolean;
  columns: ErColumn[];
  isJunction: boolean;
  /** No tiene API REST propia (superclase abstracta con estrategia JOINED) -- se muestra distinto, no es un error. */
  isAbstract: boolean;
}

function toErColumn(col: SchemaTable["columns"][number]): ErColumn {
  return {
    name: col.name,
    quoted: col.quoted,
    type: col.type,
    isPrimaryKey: col.primaryKey,
    isForeignKey: col.references !== undefined,
  };
}

function toErTableData(table: SchemaTable): ErTableData {
  return {
    tableName: displayName(table.name, table.quoted),
    quoted: table.quoted,
    isJunction: table.joinTable,
    isAbstract: table.abstractClass,
    columns: table.columns.map(toErColumn),
  };
}

export interface ErNodePlacement {
  id: string;
  data: ErTableData;
  /** null para una tabla intermedia -- no tiene clase ni posicion propia en el canonico. */
  classId: string | null;
}

/**
 * Empareja cada `SchemaTable` con su lugar en el lienzo. Las tablas de
 * clase se ubican en la posicion de esa clase (por nombre -- el esquema no
 * trae ids, trae `className`). Las tablas intermedias no tienen clase ni
 * posicion propia: se resuelven mas abajo, en toErNodes, a partir de las
 * dos tablas que referencian.
 */
export function placeSchemaTables(model: CanonicalModel, schema: SchemaPreview): ErNodePlacement[] {
  const classByName = new Map<string, ClassEntity>(model.classes.map((c) => [c.name, c]));

  return schema.tables.map((table, i) => {
    const clase = table.className ? classByName.get(table.className) : undefined;
    return {
      id: clase ? clase.id : `junction:${i}`,
      data: toErTableData(table),
      classId: clase ? clase.id : null,
    };
  });
}

/** Nombre fisico (SchemaTable.name) -> id de nodo, para resolver `references` al armar las aristas. */
export function physicalNameToNodeId(schema: SchemaPreview, placements: ErNodePlacement[]): Map<string, string> {
  const map = new Map<string, string>();
  schema.tables.forEach((table, i) => {
    map.set(table.name, placements[i].id);
  });
  return map;
}

/** Junto con ErMarkerDefs (mismos 4 ids), separado en este archivo porque ese es un componente y `only-export-components` exige que exporte solo eso. */
function multiplicityMarkerId(mult: string | undefined): string {
  switch (mult) {
    case "0..1":
      return "er-mult-0-1";
    case "1..1":
      return "er-mult-1-1";
    case "0..*":
      return "er-mult-0-many";
    case "1..*":
      return "er-mult-1-many";
    default:
      return "er-mult-1-1";
  }
}

export function erMarkerUrl(mult: string | undefined): string {
  return `url(#${multiplicityMarkerId(mult)})`;
}
