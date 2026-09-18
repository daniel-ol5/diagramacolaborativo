import { BaseEdge, useStore, type EdgeProps, type ReactFlowState } from "reactflow";
import { centerOf, rectBoundaryPoint, type Box, type Point } from "../lib/edgeGeometry";
import { erMarkerUrl } from "./erView";
import "./ErRelationshipEdge.css";

export interface ErEdgeData {
  sourceMultiplicity?: string;
  targetMultiplicity?: string;
  /** INHERITANCE no es una relacion de pata de gallo -- no tiene multiplicidad, se dibuja como linea simple. */
  variant: "crowsfoot" | "line";
}

/**
 * A diferencia de UmlRelationshipEdge, que usa el ancho/alto GUARDADO en el
 * canonico (fijo, coincide con lo renderizado), las tablas ER no tienen
 * altura fija -- hay que leer el tamaño MEDIDO de verdad por React Flow
 * (`node.width`/`node.height`, poblado despues del primer render), no un
 * dato del modelo.
 */
function medidoBoxOf(node: { positionAbsolute?: Point; position: Point; width?: number | null; height?: number | null } | undefined): Box | null {
  if (!node) return null;
  const pos = node.positionAbsolute ?? node.position;
  return { x: pos.x, y: pos.y, width: node.width ?? 200, height: node.height ?? 40 };
}

export function ErRelationshipEdge({ id, source, target, data, style }: EdgeProps<ErEdgeData>) {
  const sourceNode = useStore((s: ReactFlowState) => s.nodeInternals.get(source));
  const targetNode = useStore((s: ReactFlowState) => s.nodeInternals.get(target));
  const sourceBox = medidoBoxOf(sourceNode);
  const targetBox = medidoBoxOf(targetNode);
  if (!data || !sourceBox || !targetBox) return null;

  const sourceCenter = centerOf(sourceBox);
  const targetCenter = centerOf(targetBox);
  const sourcePoint = rectBoundaryPoint(sourceBox, targetCenter);
  const targetPoint = rectBoundaryPoint(targetBox, sourceCenter);

  const d = `M ${sourcePoint.x},${sourcePoint.y} L ${targetPoint.x},${targetPoint.y}`;

  return (
    <BaseEdge
      id={id}
      path={d}
      style={style}
      markerStart={data.variant === "crowsfoot" ? erMarkerUrl(data.sourceMultiplicity) : undefined}
      markerEnd={data.variant === "crowsfoot" ? erMarkerUrl(data.targetMultiplicity) : undefined}
    />
  );
}
