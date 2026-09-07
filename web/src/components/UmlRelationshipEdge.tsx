import { useEffect } from "react";
import { BaseEdge, useStore, type EdgeProps, type ReactFlowState } from "reactflow";
import type { ClassEntity, Relationship, RelationshipType } from "../types/canonical";
import { centerOf, offsetToward, rectBoundaryPoint, type Box, type Point } from "../lib/edgeGeometry";
import { useCollaborationActions } from "../hooks/collaborationActionsContext";
import { useCollaborationStore } from "../store/collaborationStore";
import "./UmlRelationshipEdge.css";

function boxOf(classEntity: ClassEntity): Box {
  return {
    x: classEntity.position.x,
    y: classEntity.position.y,
    width: classEntity.width,
    height: classEntity.height,
  };
}

interface EdgeVisual {
  markerStart?: string;
  markerEnd?: string;
  strokeDasharray?: string;
}

/**
 * Notacion visual por tipo (ver docs/PLAN_ARQUITECTONICO_FASE_0.txt §7/§8 y
 * el criterio del Dia 7: los 6 tipos distinguibles entre si).
 *
 * El rombo de AGGREGATION/COMPOSITION va en el extremo "todo", que NO es
 * necesariamente `source`: lo decide `owningSide`, que en este proyecto
 * marca donde vive la FK (§7, Relationship.java) — es decir, el lado
 * "parte". El "todo" es el extremo CONTRARIO a `owningSide`. Control real:
 * en barberia, Cita(source, 1..1) -{COMPOSITION}-> Pago(target, 0..1) tiene
 * owningSide=TARGET (la FK cita_id vive en pagos, D-08) porque Pago es la
 * parte — asi que el rombo va en Cita (source), no en Pago.
 */
function edgeVisual(relationship: Relationship): EdgeVisual {
  const type: RelationshipType = relationship.type;
  const owningSide = relationship.owningSide ?? "SOURCE";
  const wholeEnd: "source" | "target" = owningSide === "SOURCE" ? "target" : "source";

  switch (type) {
    case "AGGREGATION":
      return wholeEnd === "source"
        ? { markerStart: "url(#uml-diamond-hollow)" }
        : { markerEnd: "url(#uml-diamond-hollow)" };
    case "COMPOSITION":
      return wholeEnd === "source"
        ? { markerStart: "url(#uml-diamond-filled)" }
        : { markerEnd: "url(#uml-diamond-filled)" };
    case "INHERITANCE":
      // El triangulo apunta siempre al padre, que es el target (veterinaria:
      // Dueno y Veterinario -> Persona).
      return { markerEnd: "url(#uml-triangle-hollow)" };
    case "DEPENDENCY":
      return { markerEnd: "url(#uml-arrow-open)", strokeDasharray: "6,4" };
    case "ASSOCIATION":
    case "MANY_TO_MANY":
      return {};
  }
}

function endLabel(multiplicity: string | undefined, role: string | undefined): string | null {
  if (!multiplicity && !role) return null;
  if (multiplicity && role) return `${role} (${multiplicity})`;
  return multiplicity ?? role ?? null;
}

/**
 * Arista UML: dibuja el path pasando por los `waypoints` del canonico (no
 * son decoracion, sobreviven el round-trip a XMI en el Dia 13), con el
 * marcador que corresponda al tipo, las etiquetas de multiplicidad/rol
 * cerca de cada extremo, y un boton para borrarla (DELETE_RELATIONSHIP,
 * requiere lock — misma disciplina de esperar confirmacion que en
 * UmlClassNode, ver comentario ahi).
 *
 * Lee los nodos con `useStore` (reactivo) y no con `useReactFlow().getNode`
 * (una lectura puntual de `store.getState()`, sin suscripcion): al montar
 * junto con un diagrama nuevo (`key={diagramKey}` en App.tsx) las aristas
 * pueden renderizar antes de que sus nodos esten en `nodeInternals`, y con
 * el getter imperativo esa primera lectura en null quedaba fija para
 * siempre porque nada disparaba un segundo render.
 */
export function UmlRelationshipEdge({ id, data, source, target, style }: EdgeProps<Relationship>) {
  const sourceNode = useStore((s: ReactFlowState) => s.nodeInternals.get(source));
  const targetNode = useStore((s: ReactFlowState) => s.nodeInternals.get(target));
  const editingTargetId = useCollaborationStore((s) => s.editingTargetId);
  const actions = useCollaborationActions();
  const confirmedDelete = editingTargetId === id;

  useEffect(() => {
    if (!confirmedDelete) return;
    const ok = window.confirm("¿Borrar esta relación?");
    if (ok) actions?.deleteRelationship(id);
    else actions?.releaseLock(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo cuando se confirma el lock
  }, [confirmedDelete]);

  if (!data || !sourceNode || !targetNode) return null;

  const sourceBox = boxOf(sourceNode.data as ClassEntity);
  const targetBox = boxOf(targetNode.data as ClassEntity);
  const waypoints: Point[] = data.waypoints ?? [];

  const firstAimPoint = waypoints[0] ?? centerOf(targetBox);
  const lastAimPoint = waypoints[waypoints.length - 1] ?? centerOf(sourceBox);

  const sourcePoint = rectBoundaryPoint(sourceBox, firstAimPoint);
  const targetPoint = rectBoundaryPoint(targetBox, lastAimPoint);

  const pathPoints = [sourcePoint, ...waypoints, targetPoint];
  const d = "M " + pathPoints.map((p) => `${p.x},${p.y}`).join(" L ");

  const visual = edgeVisual(data);

  const sourceLabelAnchor = waypoints[0] ?? targetPoint;
  const targetLabelAnchor = waypoints[waypoints.length - 1] ?? sourcePoint;
  const sourceLabelPos = offsetToward(sourcePoint, sourceLabelAnchor, 28);
  const targetLabelPos = offsetToward(targetPoint, targetLabelAnchor, 28);

  const sourceLabel = endLabel(data.sourceMultiplicity, data.sourceRole);
  const targetLabel = endLabel(data.targetMultiplicity, data.targetRole);

  const midPoint = pathPoints[Math.floor(pathPoints.length / 2)];

  return (
    <>
      <BaseEdge
        id={id}
        path={d}
        style={{ ...style, strokeDasharray: visual.strokeDasharray }}
        markerStart={visual.markerStart}
        markerEnd={visual.markerEnd}
      />
      {sourceLabel && (
        <text x={sourceLabelPos.x} y={sourceLabelPos.y} className="uml-edge-label" textAnchor="middle">
          {sourceLabel}
        </text>
      )}
      {targetLabel && (
        <text x={targetLabelPos.x} y={targetLabelPos.y} className="uml-edge-label" textAnchor="middle">
          {targetLabel}
        </text>
      )}
      <foreignObject x={midPoint.x - 9} y={midPoint.y - 9} width={18} height={18} className="uml-edge-delete-fo">
        <button
          type="button"
          className="uml-edge-delete-btn nodrag nopan"
          title="Borrar relación"
          onClick={(e) => {
            e.stopPropagation();
            actions?.requestLock(id);
          }}
        >
          ✕
        </button>
      </foreignObject>
    </>
  );
}
