import type { Node } from "reactflow";
import type { CanonicalModel } from "../types/canonical";
import type { SchemaPreview } from "./schemaPreviewApi";
import { placeSchemaTables, type ErTableData } from "./erView";

/** CU15: una tabla por SchemaTable -- las de clase en la posicion de esa clase, las intermedias al punto medio de lo que conectan. */
export function toErNodes(model: CanonicalModel, schema: SchemaPreview): Node<ErTableData>[] {
  const placements = placeSchemaTables(model, schema);
  const classById = new Map(model.classes.map((c) => [c.id, c]));

  return placements.map((p, i) => {
    if (p.classId) {
      const clase = classById.get(p.classId)!;
      return { id: p.id, type: "erTable", position: clase.position, data: p.data };
    }

    // Tabla intermedia: sin clase ni posicion propia -- se ubica en el
    // punto medio de las dos tablas que sus columnas referencian, corrida
    // hacia abajo para no superponerse con la linea que las une.
    const table = schema.tables[i];
    const nombresReferenciados = table.columns.map((c) => c.references).filter((r): r is string => !!r);
    const tablasReferenciadas = nombresReferenciados
      .map((nombre) => schema.tables.findIndex((t) => t.name === nombre))
      .map((idx) => (idx >= 0 ? placements[idx] : null))
      .filter((pl): pl is NonNullable<typeof pl> => pl !== null)
      .map((pl) => (pl.classId ? classById.get(pl.classId) : undefined))
      .filter((c): c is NonNullable<typeof c> => c !== undefined);

    const position =
      tablasReferenciadas.length === 2
        ? {
            x: (tablasReferenciadas[0].position.x + tablasReferenciadas[1].position.x) / 2,
            y: (tablasReferenciadas[0].position.y + tablasReferenciadas[1].position.y) / 2 + 140,
          }
        : { x: 0, y: 0 };

    return { id: p.id, type: "erTable", position, data: p.data };
  });
}
