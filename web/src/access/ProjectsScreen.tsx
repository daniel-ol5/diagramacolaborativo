import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import type { Session } from "../components/LoginScreen";
import { createProject, listProjects } from "./api";
import type { Project } from "./types";
import { rolEnCastellano } from "./types";
import { NewProjectModal } from "./NewProjectModal";
import { OnboardingGuide, type GuideStep } from "../onboarding/OnboardingGuide";
import "./access.css";

const PROJECTS_GUIDE_STEPS: GuideStep[] = [
  {
    selector: '[data-tour="new-project"]',
    title: "1. Crear un proyecto",
    description:
      "¡Bienvenido a ModelCollab! Aquí creas un proyecto nuevo colocando su nombre y una descripción breve para agrupar tus diagramas y tu equipo.",
  },
  {
    selector: '[data-tour="project-list"]',
    title: "2. Entrar a tus proyectos",
    description:
      "Aquí verás todos los proyectos a los que perteneces junto con tu rol (Propietario, Editor o Lector). Haz clic en uno para abrirlo.",
  },
];

interface Props {
  session: Session;
  onLogout?: () => void;
}


/** CU08/CU09: los proyectos donde soy miembro, cada uno con mi rol -- crear, entrar. */
export function ProjectsScreen({ session, onLogout }: Props) {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showNew, setShowNew] = useState(false);
  const [showGuide, setShowGuide] = useState(false);

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
        <div className="access-page__header-right">
          <span className="access-page__session">{session.email}</span>
          <button type="button" className="onboarding-trigger-btn" onClick={() => setShowGuide(true)}>
            Guía
          </button>
          <button
            type="button"
            className="access-page__logout-btn"
            onClick={() => {
              onLogout?.();
              navigate("/login", { replace: true });
            }}
          >
            Cerrar sesión
          </button>
        </div>
      </header>
      <div className="access-page__body">
        <div className="access-page__row">
          <h1>Tus proyectos</h1>
          <button
            type="button"
            className="access-page__primary-btn"
            data-tour="new-project"
            onClick={() => setShowNew(true)}
          >
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
          <div className="access-list" data-tour="project-list">
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
                  {rolEnCastellano(p.role)}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>

      {showNew && <NewProjectModal onCreate={handleCreate} onClose={() => setShowNew(false)} />}
      <OnboardingGuide
        screenId="projects"
        steps={PROJECTS_GUIDE_STEPS}
        manualOpen={showGuide}
        onCloseManual={() => setShowGuide(false)}
      />
    </div>
  );
}
