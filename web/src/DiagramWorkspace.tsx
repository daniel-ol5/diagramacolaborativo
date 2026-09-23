import { useEffect, useMemo, useRef, useState, type ReactNode, type MouseEvent as ReactMouseEvent } from "react";
import { useNavigate } from "react-router-dom";
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
import { PackagePanel } from "./components/PackagePanel";
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
import { triggerDownload } from "./lib/download";
import { generateBackend, validateDiagram } from "./generator/api";
import type { ValidationReport } from "./generator/types";
import { ValidationDialog } from "./generator/ValidationDialog";
import { GenerateBackendModal } from "./generator/GenerateBackendModal";
import { AiCommandBox } from "./modeling/AiCommandBox";
import { VisionImportModal } from "./modeling/VisionImportModal";
import { ErTableNode } from "./modeling/ErTableNode";
import { ErRelationshipEdge } from "./modeling/ErRelationshipEdge";
import { ErMarkerDefs } from "./modeling/ErMarkerDefs";
import { toErNodes } from "./modeling/toErNodes";
import { toErEdges } from "./modeling/toErEdges";
import { fetchSchemaPreview, type SchemaPreview } from "./modeling/schemaPreviewApi";
import { exportXmi } from "./xmi/xmiApi";
import { ImportXmiModal } from "./xmi/ImportXmiModal";
import "./App.css";

const nodeTypes = { umlClass: UmlClassNode, erTable: ErTableNode };
const edgeTypes = { umlRelationship: UmlRelationshipEdge, erRelationship: ErRelationshipEdge };

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
        <span key={p.userId} className="app__roster-chip">
          {/* El color de colaborador identifica, no rellena: un punto alcanza. La
              paleta esta medida como trazo, no como fondo con texto encima --
              con relleno, la legibilidad del texto dependia de cual de los ocho
              colores le tocara (uno daba 4.41:1, por debajo de 4.5). Con el
              punto, el texto siempre esta en el color normal de la interfaz. */}
          <span className="app__roster-chip-dot" style={{ background: p.color }} aria-hidden="true" />
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
  /**
   * Proyecto al que pertenece el diagrama, o null en el demo. Importar un
   * XMI cuelga del PROYECTO y no del diagrama (crea uno nuevo al lado),
   * asi que sin esto el boton de importar no tiene a donde apuntar.
   */
  projectId: string | null;
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
export function DiagramWorkspace({ session, diagramId, projectId, toolbarLeft }: Props) {
  const [showNewClass, setShowNewClass] = useState(false);
  const [pendingConnection, setPendingConnection] = useState<PendingConnection | null>(null);
  const [validationReport, setValidationReport] = useState<{ report: ValidationReport; blocked: boolean } | null>(null);
  const [showGenerateModal, setShowGenerateModal] = useState(false);
  const [validating, setValidating] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [toolbarError, setToolbarError] = useState<string | null>(null);
  const [showAiCommand, setShowAiCommand] = useState(false);
  const [showVisionImport, setShowVisionImport] = useState(false);
  const [showXmiImport, setShowXmiImport] = useState(false);
  const [exportingXmi, setExportingXmi] = useState(false);
  const [showPackages, setShowPackages] = useState(false);
  const selectNode = usePresentationStore((s) => s.selectNode);
  const selectedNodeId = usePresentationStore((s) => s.selectedNodeId);
  const viewMode = usePresentationStore((s) => s.viewMode);
  const setViewMode = usePresentationStore((s) => s.setViewMode);
  const model = useCanonicalStore((s) => s.model);
  const reactFlowRef = useRef<ReactFlowInstance | null>(null);
  const [schemaPreview, setSchemaPreview] = useState<SchemaPreview | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const navigate = useNavigate();

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

  // La vista ER se dibuja con el esquema fisico que YA calculo el servidor
  // (GET .../schema-preview: nombres, columnas, PK/FK ya resueltas del
  // lado correcto) -- se pide de nuevo cada vez que el modelo cambia de
  // version, para que la vista ER no se quede mostrando un esquema viejo
  // mientras el resto del lienzo sigue en vivo.
  useEffect(() => {
    if (viewMode !== "er" || !diagramId || !model) return;
    let cancelado = false;
    setSchemaError(null);
    fetchSchemaPreview(diagramId, session.token)
      .then((preview) => {
        if (!cancelado) setSchemaPreview(preview);
      })
      .catch((e) => {
        if (!cancelado) setSchemaError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelado = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- se re-pide por version, no por identidad de `model`
  }, [viewMode, diagramId, model?.mutationVersion, session.token]);

  const nodes = useMemo(() => {
    if (!model) return [];
    if (viewMode === "uml") return toFlowNodes(model);
    return schemaPreview ? toErNodes(model, schemaPreview) : [];
  }, [model, viewMode, schemaPreview]);
  const edges = useMemo(() => {
    if (!model) return [];
    if (viewMode === "uml") return toFlowEdges(model);
    return schemaPreview ? toErEdges(model, schemaPreview) : [];
  }, [model, viewMode, schemaPreview]);

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
    setToolbarError(null);
    try {
      const report = await validateDiagram(diagramId, session.token);
      setValidationReport({ report, blocked: false });
    } catch (e) {
      setToolbarError(e instanceof Error ? e.message : String(e));
    } finally {
      setValidating(false);
    }
  }

  async function handleGenerate(basePackage: string) {
    if (!diagramId) return;
    setGenerating(true);
    setToolbarError(null);
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
      setToolbarError(e instanceof Error ? e.message : String(e));
    } finally {
      setGenerating(false);
    }
  }

  /**
   * CU18. El XMI se pide con fetch y se baja desde el cliente porque el
   * endpoint exige el Bearer -- ver el comentario de exportXmi(). Un VIEWER
   * tambien puede: exportar es leer.
   */
  async function handleExportXmi() {
    if (!diagramId) return;
    setExportingXmi(true);
    setToolbarError(null);
    try {
      const { blob, filename } = await exportXmi(diagramId, session.token);
      triggerDownload(blob, filename);
    } catch (e) {
      setToolbarError(e instanceof Error ? e.message : String(e));
    } finally {
      setExportingXmi(false);
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

  function handleCreateAssociationClass(className: string) {
    if (!pendingConnection || !model) return;
    const source = model.classes.find((c) => c.id === pendingConnection.sourceId);
    const target = model.classes.find((c) => c.id === pendingConnection.targetId);

    const midX = source && target
      ? Math.round((source.position.x + target.position.x) / 2)
      : 200;
    const midY = source && target
      ? Math.round((source.position.y + target.position.y) / 2) + 70
      : 200;

    const newClassId = crypto.randomUUID();

    // 1. Agregar la clase intermedia al lienzo
    collaboration.addClass({
      id: newClassId,
      name: className.trim(),
      visibility: "PUBLIC",
      isAbstract: false,
      position: { x: midX, y: midY },
      width: 220,
      height: 150,
      attributes: [],
      methods: [],
    });

    // 2. Conectar source -> intermedia
    collaboration.addRelationship({
      id: crypto.randomUUID(),
      sourceClassId: pendingConnection.sourceId,
      targetClassId: newClassId,
      type: "ASSOCIATION",
      owningSide: "TARGET",
      sourceMultiplicity: "1..1",
      targetMultiplicity: "0..*",
      isNavigable: true,
      waypoints: [],
    });

    // 3. Conectar target -> intermedia
    collaboration.addRelationship({
      id: crypto.randomUUID(),
      sourceClassId: pendingConnection.targetId,
      targetClassId: newClassId,
      type: "ASSOCIATION",
      owningSide: "TARGET",
      sourceMultiplicity: "1..1",
      targetMultiplicity: "0..*",
      isNavigable: true,
      waypoints: [],
    });

    setPendingConnection(null);
  }

  return (
    <div className="app">
      <header className="app__toolbar">
        {toolbarLeft}

        {diagramId && (
          <div className="app__toolbar-group" aria-label="Construir el modelo">
            <button type="button" className="app__new-class-btn" onClick={() => setShowNewClass(true)}>
              + Clase
            </button>
            <button type="button" onClick={() => setShowAiCommand((v) => !v)}>
              Comando
            </button>
            <button type="button" onClick={() => setShowVisionImport(true)}>
              Importar foto
            </button>
            {projectId && (
              <button type="button" onClick={() => setShowXmiImport(true)}>
                Importar XMI
              </button>
            )}
            <button type="button" onClick={() => setShowPackages((v) => !v)}>
              Paquetes
            </button>
          </div>
        )}

        {model && (
          <div className="app__toolbar-group app__view-toggle" role="group" aria-label="Cómo se ve">
            <button
              type="button"
              className={`app__view-toggle-btn${viewMode === "uml" ? " app__view-toggle-btn--active" : ""}`}
              onClick={() => setViewMode("uml")}
              title="Notación UML"
            >
              UML
            </button>
            <button
              type="button"
              className={`app__view-toggle-btn${viewMode === "er" ? " app__view-toggle-btn--active" : ""}`}
              onClick={() => setViewMode("er")}
              title="Notación Entidad-Relación (esquema físico)"
            >
              Entidad-Relación
            </button>
          </div>
        )}

        {diagramId && (
          <div className="app__toolbar-group" aria-label="Sacar algo del modelo">
            <button type="button" className="app__toolbar-btn--fixed" onClick={handleValidate} disabled={validating}>
              {validating ? "Validando…" : "Validar"}
            </button>
            <button type="button" onClick={() => setShowGenerateModal(true)}>
              Generar backend
            </button>
            <button type="button" onClick={handleExportXmi} disabled={exportingXmi}>
              {exportingXmi ? "Exportando…" : "Exportar XMI"}
            </button>
          </div>
        )}

        <div className="app__toolbar-status">
          {viewMode === "er" && !schemaPreview && !schemaError && <span className="app__collab-warning">Cargando esquema…</span>}
          {schemaError && <span className="app__collab-warning">⚠ {schemaError}</span>}
          {toolbarError && <span className="app__collab-warning">⚠ {toolbarError}</span>}
          {collaboration.sequenceGap && (
            <span className="app__collab-warning">
              ⚠ se perdió al menos un evento (esperaba #{collaboration.sequenceGap.esperaba}, llegó #
              {collaboration.sequenceGap.llego})
            </span>
          )}
        </div>

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

        <span className="app__session">{session.email}</span>
      </header>
      <div className="app__canvas">
        <UmlMarkerDefs />
        <ErMarkerDefs />
        <CollaborationToasts />
        {diagramId && <OfflineSyncBadge />}
        <ReconciliationModal />
        {diagramId && showAiCommand && (
          <AiCommandBox diagramId={diagramId} token={session.token} onClose={() => setShowAiCommand(false)} />
        )}
        {showPackages && model && (
          <PackagePanel
            model={model}
            actions={{
              requestLock: collaboration.requestLock,
              releaseLock: collaboration.releaseLock,
              renewLock: collaboration.renewLock,
              addPackage: collaboration.addPackage,
              updatePackage: collaboration.updatePackage,
              deletePackage: collaboration.deletePackage,
            }}
            onClose={() => setShowPackages(false)}
          />
        )}
        <CollaborationActionsContext.Provider
          value={{
            requestLock: collaboration.requestLock,
            releaseLock: collaboration.releaseLock,
            renewLock: collaboration.renewLock,
            renameClass: collaboration.renameClass,
            deleteClass: collaboration.deleteClass,
            resizeClass: collaboration.resizeClass,
            flushResize: collaboration.flushResize,
            addAttribute: collaboration.addAttribute,
            updateAttribute: collaboration.updateAttribute,
            deleteAttribute: collaboration.deleteAttribute,
            addMethod: collaboration.addMethod,
            updateMethod: collaboration.updateMethod,
            deleteMethod: collaboration.deleteMethod,
            deleteRelationship: collaboration.deleteRelationship,
            updateWaypoints: collaboration.updateWaypoints,
          }}
        >
          <ReactFlow
            key={`${diagramId ?? "demo"}:${viewMode}`}
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
          onCreateAssociationClass={handleCreateAssociationClass}
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
      {diagramId && showVisionImport && (
        <VisionImportModal diagramId={diagramId} token={session.token} onClose={() => setShowVisionImport(false)} />
      )}
      {projectId && showXmiImport && (
        <ImportXmiModal
          projectId={projectId}
          token={session.token}
          onClose={() => setShowXmiImport(false)}
          onImported={(diagrama) => {
            setShowXmiImport(false);
            // Importar crea un diagrama NUEVO: quedarse en el actual deja a
            // la persona mirando un lienzo que no cambio, convencida de que
            // la importacion no hizo nada.
            navigate(`/projects/${projectId}/diagrams/${diagrama.id}`);
          }}
        />
      )}
    </div>
  );
}
