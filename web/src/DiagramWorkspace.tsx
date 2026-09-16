import { useEffect, useMemo, useRef, useState, type ReactNode, type MouseEvent as ReactMouseEvent } from "react";
import ReactFlow, {
  Background,
  Controls,
  type Connection,
  type NodeChange,
  type NodeMouseHandler,
  type ReactFlowInstance,
} from "reactflow";
import "reactflow/dist/style.css";
import { UmlClassNode } from "./components/UmlClassNode";
import { UmlRelationshipEdge } from "./components/UmlRelationshipEdge";
import { UmlMarkerDefs } from "./components/UmlMarkerDefs";
import { CursorOverlay } from "./components/CursorOverlay";
import type { Session } from "./components/LoginScreen";
import { NewClassModal } from "./components/NewClassModal";
import { RelationshipFormModal } from "./components/RelationshipFormModal";
import { OfflineSyncBadge } from "./components/OfflineSyncBadge";
import { ReconciliationModal } from "./components/ReconciliationModal";
import { toFlowNodes } from "./lib/toFlowNodes";
import { toFlowEdges } from "./lib/toFlowEdges";
import { useCanonicalStore } from "./store/canonicalStore";
import { usePresentationStore } from "./store/presentationStore";
import { useCollaborationStore } from "./store/collaborationStore";
import { useCollaboration } from "./hooks/useCollaboration";
import { CollaborationActionsContext } from "./hooks/collaborationActionsContext";
import { readableTextColor } from "./lib/readableTextColor";
import { triggerDownload } from "./lib/download";
import { generateBackend, validateDiagram } from "./generator/api";
import type { ValidationReport } from "./generator/types";
import { ValidationDialog } from "./generator/ValidationDialog";
import { GenerateBackendModal } from "./generator/GenerateBackendModal";
import "./App.css";

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
        <span
          key={p.userId}
          className="app__roster-chip"
          style={{ background: p.color, color: readableTextColor(p.color) }}
        >
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

interface Props {
  session: Session;
  /** Id real del diagrama en el servidor, o null para un demo local sin colaboracion en vivo. */
  diagramId: string | null;
  /** Lo que va a la izquierda de la barra de herramientas: el selector de fixtures del demo, o un enlace de "volver" en el flujo real. */
  toolbarLeft: ReactNode;
}

/**
 * El lienzo colaborativo en si: la barra de herramientas, React Flow, los
 * modales de alta y el estado de colaboracion. Lo comparten el demo local
 * (Dia 6/7, varios fixtures sin backend) y la pantalla real de un diagrama
 * de un proyecto -- la diferencia entre los dos es solo `diagramId` (null
 * en el demo salvo para "barberia") y que hay a la izquierda de la barra.
 */
export function DiagramWorkspace({ session, diagramId, toolbarLeft }: Props) {
  const [showNewClass, setShowNewClass] = useState(false);
  const [pendingConnection, setPendingConnection] = useState<PendingConnection | null>(null);
  const [validationReport, setValidationReport] = useState<{ report: ValidationReport; blocked: boolean } | null>(null);
  const [showGenerateModal, setShowGenerateModal] = useState(false);
  const [validating, setValidating] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [generatorError, setGeneratorError] = useState<string | null>(null);
  const selectNode = usePresentationStore((s) => s.selectNode);
  const selectedNodeId = usePresentationStore((s) => s.selectedNodeId);
  const model = useCanonicalStore((s) => s.model);
  const reactFlowRef = useRef<ReactFlowInstance | null>(null);

  const collaboration = useCollaboration(diagramId, session.token, session.userId);

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

  function focusElement(elementId: string) {
    selectNode(elementId);
    reactFlowRef.current?.fitView({ nodes: [{ id: elementId }], duration: 400, maxZoom: 1.2 });
  }

  async function handleValidate() {
    if (!diagramId) return;
    setValidating(true);
    setGeneratorError(null);
    try {
      const report = await validateDiagram(diagramId, session.token);
      setValidationReport({ report, blocked: false });
    } catch (e) {
      setGeneratorError(e instanceof Error ? e.message : String(e));
    } finally {
      setValidating(false);
    }
  }

  async function handleGenerate(basePackage: string) {
    if (!diagramId) return;
    setGenerating(true);
    setGeneratorError(null);
    try {
      const resultado = await generateBackend(diagramId, session.token, basePackage);
      if (resultado.ok) {
        triggerDownload(resultado.blob, resultado.filename);
        setShowGenerateModal(false);
      } else {
        // El mismo informe que "Validar" -- errores bloqueantes impidieron
        // la descarga, y este dialogo es el que ya sabe mostrar por que.
        setShowGenerateModal(false);
        setValidationReport({ report: resultado.report, blocked: true });
      }
    } catch (e) {
      setGeneratorError(e instanceof Error ? e.message : String(e));
    } finally {
      setGenerating(false);
    }
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
        {toolbarLeft}

        {diagramId && (
          <button type="button" className="app__new-class-btn" onClick={() => setShowNewClass(true)}>
            + Clase
          </button>
        )}

        {diagramId && (
          <button type="button" onClick={handleValidate} disabled={validating}>
            {validating ? "Validando…" : "Validar"}
          </button>
        )}
        {diagramId && (
          <button type="button" onClick={() => setShowGenerateModal(true)}>
            Generar backend
          </button>
        )}
        {generatorError && <span className="app__collab-warning">⚠ {generatorError}</span>}

        {diagramId && (
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
        {diagramId && <RosterPanel />}
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
        {diagramId && <OfflineSyncBadge />}
        <ReconciliationModal />
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
            key={diagramId ?? "demo"}
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
            <Background color="var(--canvas-grid-dot)" gap={20} size={1.5} />
            <Controls />
            {diagramId && <CursorOverlay />}
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
      {showGenerateModal && (
        <GenerateBackendModal
          onGenerate={handleGenerate}
          onClose={() => setShowGenerateModal(false)}
          generating={generating}
        />
      )}
      {validationReport && (
        <ValidationDialog
          report={validationReport.report}
          blocked={validationReport.blocked}
          onClose={() => setValidationReport(null)}
          onSelectElement={focusElement}
        />
      )}
    </div>
  );
}
