import { useState, type FormEvent } from "react";
import { login, register } from "../services/authService";
import "./LoginScreen.css";

export interface Session {
  token: string;
  userId: string;
  email: string;
  fullName: string;
}

interface Props {
  onLogin: (session: Session) => void;
}

export function LoginScreen({ onLogin }: Props) {
  // CU01: LA MISMA PANTALLA ENTRA Y CREA CUENTA.
  //
  // El cliente web NO tenia forma de registrarse: ni ruta, ni boton, ni llamada. El backend
  // expone /register desde el primer dia. Nadie lo noto porque todos creabamos usuarios por la
  // API — los arneses, los verificadores, las capturas—, asi que nadie recorrio nunca la
  // costura que un usuario real recorre PRIMERO.
  //
  // Va en la misma pantalla y no en una ruta aparte: el que llega sin cuenta y el que vuelve
  // llegan al mismo lugar, y lo unico que cambia es un campo mas y el texto del boton.
  const [creando, setCreando] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nombre, setNombre] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const result = creando
          ? await register(email, password, nombre.trim())
          : await login(email, password);
      onLogin({
        token: result.token,
        userId: result.userId,
        email: result.email,
        fullName: result.fullName,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="login-screen__form" onSubmit={handleSubmit}>
        {/* En castellano rioplatense, como el resto de la aplicacion. Decia "Ingrese sus
            credenciales" mientras dos pantallas mas adelante dice "todavia no tenes proyectos":
            es la PRIMERA pantalla que se ve y arrancaba con otra voz. */}
        <h1>{creando ? "Creá tu cuenta" : "Entrá a ModelCollab"}</h1>
        {creando && (
          <label>
            Nombre y apellido
            <input
              type="text"
              value={nombre}
              onChange={(e) => setNombre(e.target.value)}
              autoFocus
              required
              maxLength={150}
            />
          </label>
        )}
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoFocus={!creando}
            required
          />
        </label>
        <label>
          Contraseña
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            // El minimo lo exige el backend (§13). Declararlo aca hace que el navegador lo
            // diga ANTES de mandar, en vez de que vuelva como un 400 que hay que interpretar.
            minLength={creando ? 8 : undefined}
          />
          {creando && (
            <span className="login-screen__ayuda">Al menos 8 caracteres</span>
          )}
        </label>
        {error && <p className="login-screen__error">{error}</p>}
        <button type="submit" disabled={loading}>
          {loading
              ? (creando ? "Creando…" : "Entrando…")
              : (creando ? "Crear cuenta" : "Entrar")}
        </button>
        <button
          type="button"
          className="login-screen__alternar"
          onClick={() => {
            setCreando(!creando);
            setError(null);
          }}
        >
          {creando ? "Ya tengo cuenta" : "No tengo cuenta — crear una"}
        </button>
      </form>
    </div>
  );
}
