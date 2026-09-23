import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { Session } from "./components/LoginScreen";
import { DiagramWorkspace } from "./DiagramWorkspace";
import { useCanonicalStore } from "./store/canonicalStore";
import { fetchDiagramSnapshot } from "./services/diagramApi";

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

  return (
    <DiagramWorkspace
      session={session}
      diagramId={diagramId}
      projectId={projectId ?? null}
      toolbarLeft={
        <Link to={`/projects/${projectId}`} className="access-page__back">
          ← Proyecto
        </Link>
      }
    />
  );
}
