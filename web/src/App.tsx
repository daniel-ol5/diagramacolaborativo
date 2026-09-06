import { useEffect, useMemo, useState } from "react";
import ReactFlow, { Background, Controls, type NodeChange, type NodeMouseHandler } from "reactflow";
import "reactflow/dist/style.css";
import barberia from "@fixtures/reference-diagrams/barberia.json";
import veterinaria from "@fixtures/reference-diagrams/veterinaria.json";
import inventario from "@fixtures/reference-diagrams/inventario.json";
import edgeCases from "@fixtures/edge-cases.json";
import type { CanonicalModel } from "./types/canonical";
import { UmlClassNode } from "./components/UmlClassNode";
import { UmlRelationshipEdge } from "./components/UmlRelationshipEdge";
import { UmlMarkerDefs } from "./components/UmlMarkerDefs";
import { LoginScreen, type Session } from "./components/LoginScreen";
import { toFlowNodes } from "./lib/toFlowNodes";
import { toFlowEdges } from "./lib/toFlowEdges";
import { useCanonicalStore } from "./store/canonicalStore";
import { usePresentationStore } from "./store/presentationStore";
import { useCollaboration } from "./hooks/useCollaboration";
import { DIAGRAMA_COLABORATIVO_ID } from "./services/config";
import "./App.css";

const DIAGRAMS: Record<string, CanonicalModel> = {
  barberia: barberia as CanonicalModel,
  veterinaria: veterinaria as CanonicalModel,
  inventario: inventario as CanonicalModel,
  "edge-cases": edgeCases as CanonicalModel,
};

// Unico diagrama que existe de verdad en el servidor (sembrado por
// scripts/seed-dev.sql con este mismo id); los otros tres son demos locales
// del Dia 6/7 sin colaboracion en vivo.
const DIAGRAMA_EN_VIVO: keyof typeof DIAGRAMS = "barberia";

const nodeTypes = { umlClass: UmlClassNode };
const edgeTypes = { umlRelationship: UmlRelationshipEdge };

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [diagramKey, setDiagramKey] = useState<keyof typeof DIAGRAMS>("barberia");
  const selectNode = usePresentationStore((s) => s.selectNode);
  const model = useCanonicalStore((s) => s.model);
  const setModel = useCanonicalStore((s) => s.setModel);

  useEffect(() => {
    setModel(DIAGRAMS[diagramKey]);
  }, [diagramKey, setModel]);

  const liveDiagramId =
    session && diagramKey === DIAGRAMA_EN_VIVO ? DIAGRAMA_COLABORATIVO_ID : null;
  const collaboration = useCollaboration(liveDiagramId, session?.token ?? null);

  const nodes = useMemo(() => (model ? toFlowNodes(model) : []), [model]);
  const edges = useMemo(() => (model ? toFlowEdges(model) : []), [model]);

  if (!session) {
    return <LoginScreen onLogin={setSession} />;
  }

  const handleNodeClick: NodeMouseHandler = (_event, node) => {
    selectNode(node.id);
  };

  function handleNodesChange(changes: NodeChange[]) {
    for (const change of changes) {
      if (change.type !== "position") continue;
      // React Flow manda la posicion en cada tick del arrastre, pero el
      // evento final (dragging:false, al soltar) viaja SIN posicion — solo
      // marca que el arrastre termino. flushMove relee la ultima posicion
      // ya aplicada al canonico y la manda sin throttle.
      if (change.position) {
        collaboration.moveClass(change.id, change.position.x, change.position.y);
      }
      if (change.dragging === false) {
        collaboration.flushMove(change.id);
      }
    }
  }

  return (
    <div className="app">
      <header className="app__toolbar">
        <label htmlFor="diagram-select">Diagrama:</label>
        <select
          id="diagram-select"
          value={diagramKey}
          onChange={(e) => setDiagramKey(e.target.value as keyof typeof DIAGRAMS)}
        >
          <option value="barberia">Barbería</option>
          <option value="veterinaria">Veterinaria</option>
          <option value="inventario">Inventario</option>
          <option value="edge-cases">Casos límite</option>
        </select>

        {liveDiagramId && (
          <span className="app__collab-status" data-status={collaboration.status}>
            {collaboration.status === "conectado" && "● en vivo"}
            {collaboration.status === "conectando" && "○ conectando…"}
            {collaboration.status === "desconectado" && "○ desconectado"}
            {collaboration.status === "error" && `⚠ ${collaboration.error ?? "error"}`}
          </span>
        )}
        {collaboration.sequenceGap && (
          <span className="app__collab-warning">
            ⚠ se perdió al menos un evento (esperaba #{collaboration.sequenceGap.esperaba}, llegó #
            {collaboration.sequenceGap.llego})
          </span>
        )}

        <span className="app__session">{session.email}</span>
      </header>
      <div className="app__canvas">
        <UmlMarkerDefs />
        <ReactFlow
          key={diagramKey}
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodeClick={handleNodeClick}
          onNodesChange={handleNodesChange}
          fitView
        >
          <Background />
          <Controls />
        </ReactFlow>
      </div>
    </div>
  );
}

export default App;
