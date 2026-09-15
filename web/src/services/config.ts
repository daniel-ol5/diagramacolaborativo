/**
 * En desarrollo, Vite sirve el front en :5173 y el backend esta en :8080 --
 * origenes distintos, hace falta la URL absoluta. En produccion, Nginx
 * sirve el front Y la API desde el MISMO origen (evita CORS del todo), asi
 * que ahi el default tiene que ser relativo al origen actual, no un
 * localhost:8080 que el navegador del usuario nunca podria alcanzar. Las
 * variables VITE_MC_* siguen pudiendo forzar cualquiera de los dos casos.
 */
export const HTTP_BASE = import.meta.env.VITE_MC_HTTP_BASE ?? (import.meta.env.DEV ? "http://localhost:8080" : "");
export const WS_URL =
  import.meta.env.VITE_MC_WS_URL ??
  (import.meta.env.DEV
    ? "ws://localhost:8080/ws"
    : `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`);

/** Diagrama sembrado por scripts/seed-dev.sql — es barberia.json, el unico que existe en el servidor. */
export const DIAGRAMA_COLABORATIVO_ID = "55555555-5555-5555-5555-555555555555";
