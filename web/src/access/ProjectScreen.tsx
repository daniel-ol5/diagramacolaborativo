import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import type { Session } from "../components/LoginScreen";
import { ApiError, createDiagram, listDiagrams, listProjects } from "./api";
import type { DiagramSummary, Project } from "./types";
import { fechaYHora } from "./types";
import { NewDiagramModal } from "./NewDiagramModal";
import { ShareModal } from "./ShareModal";
import "./access.css";

interface Props {
  session: Session;
}

/**
 * CU08/CU09 dentro de un proyecto: listar diagramas, crear, entrar. No hay
 * `GET /projects/{id}` individual -- el nombre/rol del proyecto sale de
 * `listProjects()` (la misma llamada de la pantalla anterior), filtrado por
 * id. Si no aparece ahi, es exactamente el mismo 403 ambiguo del servidor
 * ("no sos miembro" == "no existe"): no se distingue tampoco del lado del
 * cliente.
 */
export function ProjectScreen({ session }: Props) {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<Project | null | undefined>(undefined);
  const [diagrams, setDiagrams] = useState<DiagramSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [showShare, setShowShare] = useState(false);

  useEffect(() => {
    if (!projectId) return;
    let cancelado = false;
    Promise.all([listProjects(session.token), listDiagrams(session.token, projectId)])
      .then(([projects, diags]) => {
        if (cancelado) return;
        const mio = projects.find((p) => p.id === projectId) ?? null;
        setProject(mio);
        setDiagrams(diags);
      })
      .catch((e) => {
        if (cancelado) return;
        if (e instanceof ApiError && e.status === 403) {
          setForbidden(true);
        } else {
          setError(e instanceof Error ? e.message : String(e));
        }
      });
    return () => {
      cancelado = true;
    };
  }, [session.token, projectId]);

  async function handleCreate(name: string) {
    if (!projectId) return;
    try {
      const diagrama = await createDiagram(session.token, projectId, name);
      setShowNew(false);
      // "crear, entrar"
      navigate(`/projects/${projectId}/diagrams/${diagrama.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  if (forbidden) {
    return (
      <div className="access-page">
        <header className="access-page__header">
          <span className="access-page__brand">ModelCollab</span>
          <Link className="access-page__back" to="/">
            ← Proyectos
          </Link>
          <span className="access-page__session">{session.email}</span>
        </header>
        <div className="access-page__body">
          <p className="access-page__error">⚠ No tenés acceso a este proyecto.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="access-page">
      <header className="access-page__header">
        <span className="access-page__brand">ModelCollab</span>
        <Link className="access-page__back" to="/">
          ← Proyectos
        </Link>
        <span className="access-page__session">{session.email}</span>
      </header>
      <div className="access-page__body">
        <div className="access-page__row">
          <h1>{project?.name ?? "Cargando…"}</h1>
          <div className="access-page__actions">
            {project?.role === "OWNER" && (
              <button type="button" onClick={() => setShowShare(true)}>
                Compartir
              </button>
            )}
            {project && project.role !== "VIEWER" && (
              <button type="button" className="access-page__primary-btn" onClick={() => setShowNew(true)}>
                + Nuevo diagrama
              </button>
            )}
          </div>
        </div>

        {error && <p className="access-page__error">⚠ {error}</p>}

        {diagrams === null && !error && <p>Cargando…</p>}

        {diagrams && diagrams.length === 0 && (
          // Mismo caso que la lista de proyectos vacia, y este esta en el Acto 1 del guion:
          // Daniel crea el proyecto y cae aca sin ningun diagrama. Decir "no hay" y nada mas
          // lo deja buscando el boton arriba a la derecha.
          <div className="access-page__empty-box">
            <span className="access-page__empty-title">Este proyecto no tiene diagramas</span>
            <p className="access-page__empty">
              Un diagrama es el lienzo donde se modelan las clases y sus relaciones.
            </p>
            {project && project.role !== "VIEWER" && (
              <button
                type="button"
                className="access-page__primary-btn"
                onClick={() => setShowNew(true)}
              >
                + Crear el primero
              </button>
            )}
          </div>
        )}

        {diagrams && diagrams.length > 0 && (
          <div className="access-list">
            {diagrams.map((d) => (
              <button
                key={d.id}
                type="button"
                className="access-list__item"
                onClick={() => navigate(`/projects/${projectId}/diagrams/${d.id}`)}
              >
                <div className="access-list__item-name">{d.name}</div>
                <span className="access-list__item-meta">
                  actualizado {fechaYHora(d.updatedAt)}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {showNew && <NewDiagramModal onCreate={handleCreate} onClose={() => setShowNew(false)} />}
      {showShare && projectId && (
        <ShareModal session={session} projectId={projectId} onClose={() => setShowShare(false)} />
      )}
    </div>
  );
}
