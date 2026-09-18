import type { Edge } from "reactflow";
import type { CanonicalModel } from "../types/canonical";
import type { SchemaPreview } from "./schemaPreviewApi";
import { placeSchemaTables } from "./erView";
import type { ErEdgeData } from "./ErRelationshipEdge";

/**
 * CU15: las aristas salen de las columnas FK que el propio esquema ya
 * calculo (`SchemaColumn.references`) -- no de recorrer `model.relationships`
 * por su cuenta. Esto cubre los tres casos a la vez sin distinguirlos a
 * mano: una relacion normal, una tabla intermedia (sus dos columnas FK) y
 * la herencia JOINED (el id del hijo es PK y FK al padre al mismo tiempo).
 *
 * Lo unico que SI sigue saliendo del canonico son las multiplicidades
 * (para la pata de gallo) -- el esquema fisico no las tiene, no son un
 * dato de la base sino de la relacion UML. Se empareja cada FK con la
 * relacion de la que salio por (clase dueña, clase referenciada); si no
 * hay match (tipicamente la FK de herencia, que no tiene multiplicidad
 * declarada), se usa 1..1/1..1 -- que ademas es lo correcto para un id
 * compartido de herencia JOINED, no solo un default arbitrario.
 */
export function toErEdges(model: CanonicalModel, schema: SchemaPreview): Edge<ErEdgeData>[] {
  const placements = placeSchemaTables(model, schema);
  const classByName = new Map(model.classes.map((c) => [c.name, c]));
  const nameToNodeId = new Map(schema.tables.map((t, i) => [t.name, placements[i].id]));
  const nameToClassName = new Map(schema.tables.map((t) => [t.name, t.className]));

  const edges: Edge<ErEdgeData>[] = [];
  let contador = 0;

  for (const table of schema.tables) {
    const owningNodeId = nameToNodeId.get(table.name)!;
    const owningClassName = table.className;

    for (const col of table.columns) {
      if (!col.references) continue;
      const targetNodeId = nameToNodeId.get(col.references);
      if (!targetNodeId) continue; // no deberia pasar (el servidor lo garantiza), pero no reviento el lienzo si pasa

      const targetClassName = nameToClassName.get(col.references);

      let sourceMultiplicity: string | undefined;
      let targetMultiplicity: string | undefined;
      if (owningClassName && targetClassName) {
        const owningClass = classByName.get(owningClassName);
        const targetClass = classByName.get(targetClassName);
        const relacion = model.relationships.find(
          (r) =>
            r.type !== "INHERITANCE" &&
            r.type !== "DEPENDENCY" &&
            ((r.owningSide ?? "SOURCE") === "SOURCE"
              ? r.sourceClassId === owningClass?.id && r.targetClassId === targetClass?.id
              : r.targetClassId === owningClass?.id && r.sourceClassId === targetClass?.id),
        );
        if (relacion) {
          // El extremo "dueño" (el que tiene la FK) es el que la vista UML
          // ya asocia a esa clase -- mismo campo, misma lectura.
          const owningIsSource = (relacion.owningSide ?? "SOURCE") === "SOURCE";
          sourceMultiplicity = owningIsSource ? relacion.sourceMultiplicity : relacion.targetMultiplicity;
          targetMultiplicity = owningIsSource ? relacion.targetMultiplicity : relacion.sourceMultiplicity;
        }
      }

      edges.push({
        id: `schema-fk:${contador++}`,
        source: owningNodeId,
        target: targetNodeId,
        type: "erRelationship",
        data: {
          variant: "crowsfoot",
          // Sin match (tipicamente la FK de herencia JOINED): 1..1/1..1 es
          // ademas lo correcto para un id compartido, no solo un relleno.
          sourceMultiplicity: sourceMultiplicity ?? "1..1",
          targetMultiplicity: targetMultiplicity ?? "1..1",
        },
      });
    }
  }

  return edges;
}
