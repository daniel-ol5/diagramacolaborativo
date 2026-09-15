import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { Session } from "../components/LoginScreen";
import { createProject, listProjects } from "./api";
import type { Project } from "./types";
import { NewProjectModal } from "./NewProjectModal";
import "./access.css";

interface Props {
  session: Session;
}

/** CU08/CU09: los proyectos donde soy miembro, cada uno con mi rol -- crear, entrar. */
export function ProjectsScreen({ session }: Props) {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showNew, setShowNew] = useState(false);

  useEffect(() => {
    let cancelado = false;
    listProjects(session.token)
      .then((p) => {
        if (!cancelado) setProjects(p);
      })
      .catch((e) => {
        if (!cancelado) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelado = true;
    };
  }, [session.token]);

  async function handleCreate(name: string, description: string) {
    try {
      const proyecto = await createProject(session.token, name, description);
      setShowNew(false);
      // "crear, entrar": el flujo natural despues de crear es abrirlo, no
      // quedarse mirando la lista.
      navigate(`/projects/${proyecto.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="access-page">
      <header className="access-page__header">
        <span className="access-page__brand">ModelCollab</span>
        <span className="access-page__session">{session.email}</span>
      </header>
      <div className="access-page__body">
        <div className="access-page__row">
          <h1>Tus proyectos</h1>
          <button type="button" className="access-page__primary-btn" onClick={() => setShowNew(true)}>
            + Nuevo proyecto
          </button>
        </div>

        {error && <p className="access-page__error">⚠ {error}</p>}

        {projects === null && !error && <p>Cargando…</p>}

        {projects && projects.length === 0 && (
          <p className="access-page__empty">Todavía no formás parte de ningún proyecto.</p>
        )}

        {projects && projects.length > 0 && (
          <div className="access-list">
            {projects.map((p) => (
              <button
                key={p.id}
                type="button"
                className="access-list__item"
                onClick={() => navigate(`/projects/${p.id}`)}
              >
                <div>
                  <div className="access-list__item-name">{p.name}</div>
                  {p.description && <div className="access-list__item-meta">{p.description}</div>}
                </div>
                <span className={`access-role-badge${p.role === "OWNER" ? " access-role-badge--owner" : ""}`}>
                  {p.role}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {showNew && <NewProjectModal onCreate={handleCreate} onClose={() => setShowNew(false)} />}
    </div>
  );
}
