import { useState, type ReactNode } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate, type Location } from "react-router-dom";
import App from "./App";
import { DiagramScreen } from "./DiagramScreen";
import { LoginScreen, type Session } from "./components/LoginScreen";
import { ProjectsScreen } from "./access/ProjectsScreen";
import { ProjectScreen } from "./access/ProjectScreen";
import { InvitePage } from "./access/InvitePage";

/** Sin sesion, manda a /login y se acuerda de a donde volver (§CU11 punto 5: el invitado que se pierde en el login es el peor momento para que se rompa una demo). */
function RequireSession({ session, children }: { session: Session | null; children: ReactNode }) {
  const location = useLocation();
  if (!session) return <Navigate to="/login" state={{ from: location }} replace />;
  return <>{children}</>;
}

function LoginRoute({ onLogin }: { onLogin: (session: Session) => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: Location } | null)?.from;

  return (
    <LoginScreen
      onLogin={(session) => {
        onLogin(session);
        navigate(from ? `${from.pathname}${from.search}` : "/", { replace: true });
      }}
    />
  );
}

/**
 * Raiz de ruteo (P1. Acceso y proyectos + el lienzo). Antes de esto,
 * `App.tsx` era a la vez el login-gate Y el lienzo: entraba directo a un
 * diagrama fijo porque no habia otro lado a donde ir. Ahora el login vive
 * ARRIBA de las rutas y cada pantalla recibe la sesion por prop, no por
 * estado propio.
 */
export function AppRoot() {
  const [session, setSession] = useState<Session | null>(null);

  return (
    <Routes>
      <Route path="/login" element={<LoginRoute onLogin={setSession} />} />
      <Route path="/invite/:token" element={<InvitePage session={session} />} />
      <Route
        path="/"
        element={
          <RequireSession session={session}>
            <ProjectsScreen session={session!} />
          </RequireSession>
        }
      />
      <Route
        path="/projects/:projectId"
        element={
          <RequireSession session={session}>
            <ProjectScreen session={session!} />
          </RequireSession>
        }
      />
      <Route
        path="/projects/:projectId/diagrams/:diagramId"
        element={
          <RequireSession session={session}>
            <DiagramScreen session={session!} />
          </RequireSession>
        }
      />
      <Route
        path="/demo"
        element={
          <RequireSession session={session}>
            <App session={session!} />
          </RequireSession>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
