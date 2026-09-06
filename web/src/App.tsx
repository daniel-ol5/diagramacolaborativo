import { useMemo, useState } from "react";
import ReactFlow, { Background, Controls, type NodeMouseHandler } from "reactflow";
import "reactflow/dist/style.css";
import barberia from "@fixtures/reference-diagrams/barberia.json";
import veterinaria from "@fixtures/reference-diagrams/veterinaria.json";
import inventario from "@fixtures/reference-diagrams/inventario.json";
import edgeCases from "@fixtures/edge-cases.json";
import type { CanonicalModel } from "./types/canonical";
import { UmlClassNode } from "./components/UmlClassNode";
import { UmlRelationshipEdge } from "./components/UmlRelationshipEdge";
import { UmlMarkerDefs } from "./components/UmlMarkerDefs";
import { toFlowNodes } from "./lib/toFlowNodes";
import { toFlowEdges } from "./lib/toFlowEdges";
import { usePresentationStore } from "./store/presentationStore";
import "./App.css";

const DIAGRAMS: Record<string, CanonicalModel> = {
  barberia: barberia as CanonicalModel,
  veterinaria: veterinaria as CanonicalModel,
  inventario: inventario as CanonicalModel,
  "edge-cases": edgeCases as CanonicalModel,
};

const nodeTypes = { umlClass: UmlClassNode };
const edgeTypes = { umlRelationship: UmlRelationshipEdge };

function App() {
  const [diagramKey, setDiagramKey] = useState<keyof typeof DIAGRAMS>("barberia");
  const selectNode = usePresentationStore((s) => s.selectNode);

  const model = DIAGRAMS[diagramKey];
  const nodes = useMemo(() => toFlowNodes(model), [model]);
  const edges = useMemo(() => toFlowEdges(model), [model]);

  const handleNodeClick: NodeMouseHandler = (_event, node) => {
    selectNode(node.id);
  };

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
