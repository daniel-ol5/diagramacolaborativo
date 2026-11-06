import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Session } from "./components/LoginScreen";
import { DiagramWorkspace } from "./DiagramWorkspace";
import { useCanonicalStore } from "./store/canonicalStore";
import { fetchDiagramSnapshot } from "./services/diagramApi";
import { StompConnectionProvider } from "./assistant/shell/StompConnectionProvider";
import { AssistantPanel } from "./assistant/shell/AssistantPanel";
import { ChatPanel } from "./assistant/chat/ChatPanel";
import { ProjectChatFeed } from "./assistant/chat/ProjectChatFeed";
import { useChatStore } from "./assistant/chat/chatStore";

interface Props {
  session: Session;
}

/**
 * Un diagrama real de un proyecto (a diferencia de App.tsx/demo, que usa
 * fixtures locales): el snapshot inicial se pide al servidor -- el
 * hardcodeo de `DIAGRAMS[diagramKey]` era exactamente el motivo por el que
 * antes de las pantallas no habia forma de abrir un diagrama que no fuera
 * el sembrado a mano.
 */
export function DiagramScreen({ session }: Props) {
  const { projectId, diagramId } = useParams<{ projectId: string; diagramId: string }>();
  const setModel = useCanonicalStore((s) => s.setModel);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  // CU19: el panel del asistente se monta aca (no dentro del lienzo).
  const [assistantOpen, setAssistantOpen] = useState(false);
  // CU23: no leidos del chat, para la insignia del boton "Chat IA".
  const unread = useChatStore((s) => (projectId && s.projectId === projectId ? s.unreadCount : 0));

  useEffect(() => {
    if (!diagramId) return;
    let cancelado = false;
    setLoaded(false);
    fetchDiagramSnapshot(diagramId, session.token)
      .then((snapshot) => {
        if (cancelado) return;
        setModel(snapshot);
        setLoaded(true);
      })
      .catch((e) => {
        if (!cancelado) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelado = true;
    };
  }, [diagramId, session.token, setModel]);

  if (error) {
    return (
      <div style={{ padding: "var(--space-4)" }}>
        <p className="access-page__error">⚠ {error}</p>
        <Link to={projectId ? `/projects/${projectId}` : "/"}>← Volver</Link>
      </div>
    );
  }

  if (!loaded || !diagramId) {
    return <p style={{ padding: "var(--space-4)" }}>Cargando diagrama…</p>;
  }

  // Una sola conexion STOMP para el lienzo y el chat del proyecto.
  // Dentro de la rama "cargado": se monta y desmonta junto con el lienzo, como antes.
  return (
    <StompConnectionProvider diagramId={diagramId} token={session.token}>
      <DiagramWorkspace
        session={session}
        diagramId={diagramId}
        projectId={projectId ?? null}
        assistant={
          projectId ? { open: assistantOpen, onToggle: () => setAssistantOpen((v) => !v), unread } : undefined
        }
        sidePanel={
          projectId && assistantOpen ? (
            <AssistantPanel onClose={() => setAssistantOpen(false)}>
              <ChatPanel
                projectId={projectId}
                diagramId={diagramId}
                token={session.token}
                myUserId={session.userId}
                myName={session.fullName}
              />
            </AssistantPanel>
          ) : null
        }
        toolbarLeft={
          <Link to={`/projects/${projectId}`} className="access-page__back">
            ← Proyecto
          </Link>
        }
      />
      {/* El chat sigue recibiendo con el panel cerrado (no leidos). Va DESPUES
          del lienzo: asi registra sus handlers despues y, en cada CONNECT, las
          4 suscripciones del lienzo salen primero y en el mismo orden de antes. */}
      {projectId && <ProjectChatFeed projectId={projectId} token={session.token} myUserId={session.userId} />}
    </StompConnectionProvider>
  );
}
