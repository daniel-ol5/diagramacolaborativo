import { useEffect, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import type { Session } from "../components/LoginScreen";
import { acceptInvitation, previewInvitation } from "./api";
import type { InvitationPreview } from "./types";
import { rolEnCastellano } from "./types";
import "./InvitePage.css";

interface Props {
  session: Session | null;
}

const RAZON_TEXTO: Record<string, string> = {
  EXPIRED: "Este enlace venció.",
  REVOKED: "Este enlace fue revocado.",
  NOT_FOUND: "Este enlace no existe.",
};

/**
 * CU11, ruta /invite/{token}. Es publica a proposito (InvitationController):
 * hay que poder ver a que te invitaron ANTES de decidir si te registras.
 *
 * Lo unico que es responsabilidad exclusiva de este lado (no del contrato
 * del servidor): si no hay sesion, mandar a /login y traer de vuelta a
 * ESTE enlace despues -- si se pierde, es el peor momento posible para que
 * se rompa una demostracion.
 */
export function InvitePage({ session }: Props) {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [accepting, setAccepting] = useState(false);

  useEffect(() => {
    if (!token) return;
    let cancelado = false;
    previewInvitation(token)
      .then((p) => {
        if (!cancelado) setPreview(p);
      })
      .catch((e) => {
        if (!cancelado) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelado = true;
    };
  }, [token]);

  async function handleAccept() {
    if (!token || !session) return;
    setAccepting(true);
    setError(null);
    try {
      const { projectId } = await acceptInvitation(session.token, token);
      navigate(`/projects/${projectId}`, { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setAccepting(false);
    }
  }

  function handleGoToLogin() {
    navigate("/login", { state: { from: location }, replace: false });
  }

  return (
    <div className="invite-page">
      <div className="invite-page__card">
        {error && <p className="invite-page__error">⚠ {error}</p>}

        {!error && !preview && <p>Consultando la invitación…</p>}

        {!error && preview && !preview.valid && (
          <>
            <h1>Enlace no disponible</h1>
            <p>{RAZON_TEXTO[preview.reason ?? ""] ?? "Este enlace no es válido."}</p>
            <p className="invite-page__nota">
              Pedile al dueño del proyecto que te mande uno nuevo.
            </p>
            {/* Sin esto la tarjeta es un callejón: quien llega con un enlace vencido se queda
                mirando un cartel sin ningún lado a donde ir. */}
            <button type="button" className="invite-page__action" onClick={() => navigate("/")}>
              Ir a ModelCollab
            </button>
          </>
        )}

        {!error && preview && preview.valid && (
          <>
            <h1>
              Te invitaron a {preview.projectName} como{" "}
              <span className="invite-page__role">{rolEnCastellano(preview.role)}</span>
            </h1>
            {session ? (
              <>
                {/* Con qué cuenta se entra al proyecto. Importa porque el enlace se abre en el
                    navegador que uno TIENE ABIERTO, que puede tener la sesión de otra persona:
                    aceptar con la cuenta equivocada mete al proyecto a quien no era. */}
                <p className="invite-page__nota">
                  Vas a aceptarla como <strong>{session.fullName || session.email}</strong>.
                </p>
                <button type="button" className="invite-page__action" onClick={handleAccept} disabled={accepting}>
                  {accepting ? "Aceptando…" : "Aceptar invitación"}
                </button>
              </>
            ) : (
              <>
                {/* DECIA SOLO "Iniciar sesión para aceptar", y quien recibe una invitación es
                    justamente la persona que todavía NO tiene cuenta. La pantalla de crear
                    cuenta existe desde 061ad6e y esta —la única a la que llega alguien de
                    afuera— seguía sin nombrarla. Misma costura, otra vez: la pieza nueva no
                    llegó al lugar donde hacía falta. */}
                <p className="invite-page__nota">
                  Necesitás una cuenta de ModelCollab para aceptarla. Si todavía no tenés, la creás
                  en el mismo paso.
                </p>
                <button type="button" className="invite-page__action" onClick={handleGoToLogin}>
                  Entrar o crear cuenta
                </button>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}
