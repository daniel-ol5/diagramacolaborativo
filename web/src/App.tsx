import { useMemo, useState } from "react";
import ReactFlow, { Background, Controls, type NodeMouseHandler } from "reactflow";
import "reactflow/dist/style.css";
import barberia from "@fixtures/barberia.json";
import veterinaria from "@fixtures/veterinaria.json";
import inventario from "@fixtures/inventario.json";
import type { CanonicalModel } from "./types/canonical";
import { UmlClassNode } from "./components/UmlClassNode";
import { toFlowNodes } from "./lib/toFlowNodes";
import { usePresentationStore } from "./store/presentationStore";
import "./App.css";

const DIAGRAMS: Record<string, CanonicalModel> = {
  barberia: barberia as CanonicalModel,
  veterinaria: veterinaria as CanonicalModel,
  inventario: inventario as CanonicalModel,
};

const nodeTypes = { umlClass: UmlClassNode };

function App() {
  const [diagramKey, setDiagramKey] = useState<keyof typeof DIAGRAMS>("barberia");
  const selectNode = usePresentationStore((s) => s.selectNode);

  const nodes = useMemo(() => toFlowNodes(DIAGRAMS[diagramKey]), [diagramKey]);

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
        </select>
      </header>
      <div className="app__canvas">
        <ReactFlow
          key={diagramKey}
          nodes={nodes}
          edges={[]}
          nodeTypes={nodeTypes}
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
