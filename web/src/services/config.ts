/** Backend local del Dia 2 en adelante. Override con variables VITE_* si hiciera falta. */
export const HTTP_BASE = import.meta.env.VITE_MC_HTTP_BASE ?? "http://localhost:8080";
export const WS_URL = import.meta.env.VITE_MC_WS_URL ?? "ws://localhost:8080/ws";

/** Diagrama sembrado por scripts/seed-dev.sql — es barberia.json, el unico que existe en el servidor. */
export const DIAGRAMA_COLABORATIVO_ID = "55555555-5555-5555-5555-555555555555";
