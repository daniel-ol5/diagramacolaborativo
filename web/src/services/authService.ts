import { HTTP_BASE } from "./config";

/** Espejo de AuthResponse (backend/.../auth/dto/AuthResponse.java, contrato D-11). */
export interface AuthResponse {
  token: string;
  tokenType: string;
  userId: string;
  email: string;
  fullName: string;
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${HTTP_BASE}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    const detalle =
      res.status === 401 ? "credenciales invalidas" : `HTTP ${res.status}${text ? `: ${text.slice(0, 200)}` : ""}`;
    throw new Error(`no se pudo iniciar sesion (${detalle})`);
  }
  return res.json();
}

/**
 * CU01: crear una cuenta.
 *
 * El backend expone /register desde el primer dia y el cliente web NO lo llamaba desde ningun
 * lado — no habia pantalla, ni ruta, ni este metodo. Se descubrio a dos dias de la defensa, y
 * el motivo de que no se notara es que TODOS creamos usuarios por la API: los arneses, los
 * verificadores y las pruebas. La costura estaba rota justo donde nadie miraba, que es el
 * lugar donde este proyecto ya fallo cuatro veces.
 *
 * Devuelve la sesion ya iniciada, igual que el login: el backend contesta un AuthResponse
 * completo al registrar, asi que no hace falta pedir la contrasenia dos veces.
 */
export async function register(
  email: string,
  password: string,
  fullName: string,
): Promise<AuthResponse> {
  const res = await fetch(`${HTTP_BASE}/api/v1/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, fullName }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    // El 409 es el caso que mas va a ver una persona, y merece decirse en castellano en vez de
    // como un codigo: "ya hay una cuenta con ese correo" se resuelve solo, "HTTP 409" no.
    const detalle =
      res.status === 409
        ? "ya existe una cuenta con ese correo"
        : `HTTP ${res.status}${text ? `: ${text.slice(0, 200)}` : ""}`;
    throw new Error(`no se pudo crear la cuenta (${detalle})`);
  }
  return res.json();
}
