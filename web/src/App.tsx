import { useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent } from "react";
import ReactFlow, {
  Background,
  Controls,
  type Connection,
  type NodeChange,
  type NodeMouseHandler,
  type ReactFlowInstance,
} from "reactflow";
import "reactflow/dist/style.css";
import barberia from "@fixtures/reference-diagrams/barberia.json";
import veterinaria from "@fixtures/reference-diagrams/veterinaria.json";
import inventario from "@fixtures/reference-diagrams/inventario.json";
import edgeCases from "@fixtures/edge-cases.json";
import type { CanonicalModel } from "./types/canonical";
import { UmlClassNode } from "./components/UmlClassNode";
import { UmlRelationshipEdge } from "./components/UmlRelationshipEdge";
import { UmlMarkerDefs } from "./components/UmlMarkerDefs";
import { CursorOverlay } from "./components/CursorOverlay";
import { LoginScreen, type Session } from "./components/LoginScreen";
import { NewClassModal } from "./components/NewClassModal";
import { RelationshipFormModal } from "./components/RelationshipFormModal";
import { toFlowNodes } from "./lib/toFlowNodes";
import { toFlowEdges } from "./lib/toFlowEdges";
import { useCanonicalStore } from "./store/canonicalStore";
import { usePresentationStore } from "./store/presentationStore";
import { useCollaborationStore } from "./store/collaborationStore";
import { useCollaboration } from "./hooks/useCollaboration";
import { CollaborationActionsContext } from "./hooks/collaborationActionsContext";
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

/** Toasts de lock: se auto-descartan, no se acumulan uno atrás del otro. */
function CollaborationToasts() {
  const deniedNotice = useCollaborationStore((s) => s.deniedNotice);
  const setDeniedNotice = useCollaborationStore((s) => s.setDeniedNotice);
  const lostLockNotice = useCollaborationStore((s) => s.lostLockNotice);
  const setLostLockNotice = useCollaborationStore((s) => s.setLostLockNotice);

  useEffect(() => {
    if (!deniedNotice) return;
    const timer = setTimeout(() => setDeniedNotice(null), 4000);
    return () => clearTimeout(timer);
  }, [deniedNotice, setDeniedNotice]);

  useEffect(() => {
    if (!lostLockNotice) return;
    const timer = setTimeout(() => setLostLockNotice(null), 5000);
    return () => clearTimeout(timer);
  }, [lostLockNotice, setLostLockNotice]);

  if (!deniedNotice && !lostLockNotice) return null;

  return (
    <div className="app__toasts">
      {deniedNotice && (
        <div className="app__toast">Elemento en edición por {deniedNotice.heldBy}</div>
      )}
      {lostLockNotice && <div className="app__toast app__toast--warning">{lostLockNotice}</div>}
    </div>
  );
}

/** Quienes estan en la sala ahora. Dos pestañas del mismo usuario cuentan como una sola persona: lo dedupe el servidor. */
function RosterPanel() {
  const roster = useCollaborationStore((s) => s.roster);
  if (roster.length === 0) return null;
  return (
    <div className="app__roster" title={roster.map((p) => p.userName).join(", ")}>
      {roster.map((p) => (
        <span key={p.userId} className="app__roster-chip" style={{ background: p.color }}>
          {p.userName.split(" ")[0]}
        </span>
      ))}
    </div>
  );
}

interface PendingConnection {
  sourceId: string;
  sourceName: string;
  targetId: string;
  targetName: string;
}

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [diagramKey, setDiagramKey] = useState<keyof typeof DIAGRAMS>("barberia");
  const [showNewClass, setShowNewClass] = useState(false);
  const [pendingConnection, setPendingConnection] = useState<PendingConnection | null>(null);
  const selectNode = usePresentationStore((s) => s.selectNode);
  const selectedNodeId = usePresentationStore((s) => s.selectedNodeId);
  const model = useCanonicalStore((s) => s.model);
  const setModel = useCanonicalStore((s) => s.setModel);
  const reactFlowRef = useRef<ReactFlowInstance | null>(null);

  useEffect(() => {
    setModel(DIAGRAMS[diagramKey]);
  }, [diagramKey, setModel]);

  const liveDiagramId =
    session && diagramKey === DIAGRAMA_EN_VIVO ? DIAGRAMA_COLABORATIVO_ID : null;
  const collaboration = useCollaboration(liveDiagramId, session?.token ?? null, session?.userId ?? null);

  // El error de una mutacion rechazada (LOCK_REQUIRED, DUPLICATE_ID, etc.)
  // se auto-descarta, igual que los demas toasts — si no, un DUPLICATE_ID
  // de hace un minuto se queda pegado en la barra para siempre.
  useEffect(() => {
    if (!collaboration.error) return;
    const timer = setTimeout(() => collaboration.clearError(), 6000);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo cuando cambia el mensaje
  }, [collaboration.error]);

  const nodes = useMemo(() => (model ? toFlowNodes(model) : []), [model]);
  const edges = useMemo(() => (model ? toFlowEdges(model) : []), [model]);

  if (!session) {
    return <LoginScreen onLogin={setSession} />;
  }

  const handleNodeClick: NodeMouseHandler = (_event, node) => {
    selectNode(node.id);
  };

  function handlePaneMouseMove(event: ReactMouseEvent) {
    const instance = reactFlowRef.current;
    if (!instance) return;
    const flowPos = instance.screenToFlowPosition({ x: event.clientX, y: event.clientY });
    collaboration.sendCursor(flowPos.x, flowPos.y, selectedNodeId);
  }

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

  function handleCreateClass(name: string) {
    const instance = reactFlowRef.current;
    const center = instance
      ? instance.screenToFlowPosition({ x: window.innerWidth / 2, y: window.innerHeight / 2 })
      : { x: 200, y: 200 };
    const jitter = () => Math.round(Math.random() * 60 - 30);
    collaboration.addClass({
      id: crypto.randomUUID(),
      name,
      visibility: "PUBLIC",
      isAbstract: false,
      position: { x: center.x + jitter(), y: center.y + jitter() },
      width: 240,
      height: 180,
      attributes: [],
      methods: [],
    });
    setShowNewClass(false);
  }

  function handleConnect(connection: Connection) {
    if (!connection.source || !connection.target || !model) return;
    if (connection.source === connection.target) return; // una clase no se conecta consigo misma
    const source = model.classes.find((c) => c.id === connection.source);
    const target = model.classes.find((c) => c.id === connection.target);
    if (!source || !target) return;
    setPendingConnection({
      sourceId: source.id,
      sourceName: source.name,
      targetId: target.id,
      targetName: target.name,
    });
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
          <button type="button" className="app__new-class-btn" onClick={() => setShowNewClass(true)}>
            + Clase
          </button>
        )}

        {liveDiagramId && (
          <span
            className="app__collab-status"
            data-status={collaboration.error ? "error" : collaboration.status}
          >
            {collaboration.error && `⚠ ${collaboration.error}`}
            {!collaboration.error && collaboration.status === "conectado" && "● en vivo"}
            {!collaboration.error && collaboration.status === "conectando" && "○ conectando…"}
            {!collaboration.error && collaboration.status === "desconectado" && "○ desconectado"}
            {!collaboration.error && collaboration.status === "error" && "⚠ error de conexión"}
          </span>
        )}
        {liveDiagramId && <RosterPanel />}
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
        <CollaborationToasts />
        <CollaborationActionsContext.Provider
          value={{
            requestLock: collaboration.requestLock,
            releaseLock: collaboration.releaseLock,
            renewLock: collaboration.renewLock,
            renameClass: collaboration.renameClass,
            deleteClass: collaboration.deleteClass,
            addAttribute: collaboration.addAttribute,
            updateAttribute: collaboration.updateAttribute,
            deleteAttribute: collaboration.deleteAttribute,
            deleteRelationship: collaboration.deleteRelationship,
          }}
        >
          <ReactFlow
            key={diagramKey}
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            onNodeClick={handleNodeClick}
            onNodesChange={handleNodesChange}
            onConnect={handleConnect}
            onInit={(instance) => {
              reactFlowRef.current = instance;
            }}
            onPaneMouseMove={handlePaneMouseMove}
            fitView
          >
            <Background />
            <Controls />
            {liveDiagramId && <CursorOverlay />}
          </ReactFlow>
        </CollaborationActionsContext.Provider>
      </div>

      {showNewClass && <NewClassModal onCreate={handleCreateClass} onClose={() => setShowNewClass(false)} />}
      {pendingConnection && (
        <RelationshipFormModal
          sourceId={pendingConnection.sourceId}
          sourceName={pendingConnection.sourceName}
          targetId={pendingConnection.targetId}
          targetName={pendingConnection.targetName}
          onSubmit={(relationship) => {
            collaboration.addRelationship(relationship);
            setPendingConnection(null);
          }}
          onClose={() => setPendingConnection(null)}
        />
      )}
    </div>
  );
}

export default App;
