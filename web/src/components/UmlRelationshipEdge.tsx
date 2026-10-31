import { useEffect, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { BaseEdge, useReactFlow, useStore, type EdgeProps, type ReactFlowState } from "reactflow";
import type { ClassEntity, Relationship, RelationshipType, Waypoint } from "../types/canonical";
import { centerOf, offsetToward, rectBoundaryPoint, type Box, type Point } from "../lib/edgeGeometry";
import { useCollaborationActions } from "../hooks/collaborationActionsContext";
import { useCollaborationStore } from "../store/collaborationStore";
import { useOfflineStore } from "../store/offlineStore";
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
 * CU04 (quiebres de linea): a que segmento de la polilinea `puntos` cae mas
 * cerca `p`, y donde exactamente sobre ese segmento -- para saber en que
 * posicion de `waypoints` insertar un quiebre nuevo al hacer doble clic
 * sobre la arista, y con que coordenadas.
 */
function segmentoMasCercano(puntos: Point[], p: Point): { indiceSegmento: number; proyeccion: Point } {
  let mejor = { indiceSegmento: 0, proyeccion: puntos[0], distancia: Infinity };
  for (let i = 0; i < puntos.length - 1; i++) {
    const a = puntos[i];
    const b = puntos[i + 1];
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const largoCuadrado = dx * dx + dy * dy || 1;
    let t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / largoCuadrado;
    t = Math.max(0, Math.min(1, t));
    const proyeccion = { x: a.x + dx * t, y: a.y + dy * t };
    const distancia = Math.hypot(p.x - proyeccion.x, p.y - proyeccion.y);
    if (distancia < mejor.distancia) mejor = { indiceSegmento: i, proyeccion, distancia };
  }
  return mejor;
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
  const isOffline = useOfflineStore((s) => s.state === "OFFLINE");
  const actions = useCollaborationActions();
  const { screenToFlowPosition } = useReactFlow();
  const [deleteRequested, setDeleteRequested] = useState(false);
  // CU04: quiebres de linea. UPDATE_WAYPOINTS es LWW libre (sin candado,
  // igual que MOVE_CLASS) -- un solo mensaje al soltar el arrastre, no un
  // stream throttled: mientras se arrastra, el punto vive solo en este
  // estado local (nadie mas necesita verlo moverse en tiempo real).
  const [arrastrando, setArrastrando] = useState<{ indice: number; punto: Point } | null>(null);
  // Sin conexion se omite la adquisicion de candado (§8.4): el pedido local
  // alcanza para confirmar, no hace falta esperar LOCK_ACQUIRED.
  const confirmedDelete = deleteRequested && (isOffline || editingTargetId === id);

  useEffect(() => {
    if (!confirmedDelete) return;
    const ok = window.confirm("¿Borrar esta relación?");
    if (ok) actions?.deleteRelationship(id);
    else actions?.releaseLock(id);
    setDeleteRequested(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo cuando se confirma el lock
  }, [confirmedDelete]);

  if (!data || !sourceNode || !targetNode) return null;

  const sourceBox = boxOf(sourceNode.data as ClassEntity);
  const targetBox = boxOf(targetNode.data as ClassEntity);
  const waypointsBase: Point[] = data.waypoints ?? [];
  // Efectivos para dibujar: si hay un quiebre en arrastre, se ve en su
  // posicion nueva ya (sin esperar la confirmacion del servidor) -- pero
  // `waypointsBase` (no este) es lo que se guarda/publica al soltar.
  const waypoints: Point[] = arrastrando
    ? waypointsBase.map((w, i) => (i === arrastrando.indice ? arrastrando.punto : w))
    : waypointsBase;

  function iniciarArrastreDeQuiebre(indice: number, e: ReactPointerEvent<SVGCircleElement>) {
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    setArrastrando({ indice, punto: waypointsBase[indice] });
  }

  function duranteArrastreDeQuiebre(e: ReactPointerEvent<SVGCircleElement>) {
    if (!arrastrando) return;
    e.stopPropagation();
    setArrastrando({ indice: arrastrando.indice, punto: screenToFlowPosition({ x: e.clientX, y: e.clientY }) });
  }

  function terminarArrastreDeQuiebre(e: ReactPointerEvent<SVGCircleElement>) {
    if (!arrastrando) return;
    e.stopPropagation();
    const finales = waypointsBase.map((w, i) => (i === arrastrando.indice ? arrastrando.punto : w));
    actions?.updateWaypoints(id, finales as Waypoint[]);
    setArrastrando(null);
  }

  function borrarQuiebre(indice: number, e: ReactMouseEvent) {
    e.stopPropagation();
    actions?.updateWaypoints(id, waypointsBase.filter((_, i) => i !== indice) as Waypoint[]);
  }

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

  function agregarQuiebreEnDobleClick(e: ReactMouseEvent<SVGPathElement>) {
    e.stopPropagation();
    const puntoClick = screenToFlowPosition({ x: e.clientX, y: e.clientY });
    const { indiceSegmento, proyeccion } = segmentoMasCercano(pathPoints, puntoClick);
    const nuevos = [...waypointsBase.slice(0, indiceSegmento), proyeccion, ...waypointsBase.slice(indiceSegmento)];
    actions?.updateWaypoints(id, nuevos as Waypoint[]);
  }

  return (
    <>
      <BaseEdge
        id={id}
        path={d}
        style={{ ...style, strokeDasharray: visual.strokeDasharray }}
        markerStart={visual.markerStart}
        markerEnd={visual.markerEnd}
      />
      {/* Ancho de acierto mas grande que el trazo visible, invisible, solo
          para el doble clic de agregar un quiebre -- el trazo real (mas
          fino) haria falta apuntar con demasiada precision. */}
      <path
        d={d}
        fill="none"
        stroke="transparent"
        strokeWidth={16}
        className="nodrag nopan uml-edge-hit-area"
        onDoubleClick={agregarQuiebreEnDobleClick}
      />
      {waypoints.map((w, i) => (
        <circle
          key={i}
          cx={w.x}
          cy={w.y}
          r={5}
          className="uml-edge-waypoint-handle nodrag nopan"
          onPointerDown={(e) => iniciarArrastreDeQuiebre(i, e)}
          onPointerMove={duranteArrastreDeQuiebre}
          onPointerUp={terminarArrastreDeQuiebre}
          onDoubleClick={(e) => borrarQuiebre(i, e)}
        >
          <title>Arrastrar para mover, doble clic para borrar</title>
        </circle>
      ))}
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
      <foreignObject x={midPoint.x - 12} y={midPoint.y - 12} width={24} height={24} className="uml-edge-delete-fo">
        <button
          type="button"
          className="uml-edge-delete-btn nodrag nopan"
          title="Borrar relación"
          onClick={(e) => {
            e.stopPropagation();
            setDeleteRequested(true);
            if (!isOffline) actions?.requestLock(id);
          }}
        >
          🗑
        </button>
      </foreignObject>
    </>
  );
}
