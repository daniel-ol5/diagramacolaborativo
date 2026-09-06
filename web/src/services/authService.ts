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
