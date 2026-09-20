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

/**
 * El rol, escrito para una persona y no para la base de datos.
 *
 * OWNER/EDITOR/VIEWER son los valores del CHECK de §13 y viajan asi por la API, pero en
 * pantalla eran el enum crudo asomandose: el resto de la interfaz esta en castellano. De paso
 * la pastilla ocupa menos, que era el otro problema.
 */
const ROL_EN_CASTELLANO: Record<string, string> = {
  OWNER: "Dueño",
  EDITOR: "Editor",
  VIEWER: "Lector",
};

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
          <div className="access-page__empty-box">
            <span className="access-page__empty-title">Todavía no tenés proyectos</span>
            {/* Un estado vacio que solo dice "no hay nada" deja a la persona parada. Esta es
                la primera pantalla de alguien recien registrado, asi que dice cual es el
                proximo paso y le da el boton para darlo sin tener que buscarlo arriba. */}
            <p className="access-page__empty">
              Un proyecto agrupa tus diagramas y a la gente con la que los compartís.
            </p>
            <button
              type="button"
              className="access-page__primary-btn"
              onClick={() => setShowNew(true)}
            >
              + Crear el primero
            </button>
          </div>
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
                <div className="access-list__item-text">
                  <div className="access-list__item-name">{p.name}</div>
                  {p.description && <div className="access-list__item-meta">{p.description}</div>}
                </div>
                <span className={`access-role-badge${p.role === "OWNER" ? " access-role-badge--owner" : ""}`}>
                  {ROL_EN_CASTELLANO[p.role] ?? p.role}
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
