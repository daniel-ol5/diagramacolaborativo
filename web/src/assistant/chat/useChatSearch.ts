import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchChatHistory } from "./chatApi";
import { mergeChatMessages } from "./chatStore";
import { consultaDe, textoDeBusqueda, textoDeErrorDeBusqueda, type ChatFilter } from "./chatSearch";
import type { ChatMessage } from "./types";

/** Espera desde la ultima tecla hasta buscar. */
const DEBOUNCE_MS = 300;
const TANDA = 50;

interface Resultados {
  mensajes: ChatMessage[];
  hasMore: boolean;
  estado: "idle" | "loading" | "ready" | "error";
  error: string | null;
  olderLoading: boolean;
  olderError: string | null;
}

const VACIO: Resultados = {
  mensajes: [],
  hasMore: false,
  estado: "idle",
  error: null,
  olderLoading: false,
  olderError: null,
};

export interface ChatSearch extends Resultados {
  /** Campo de busqueda desplegado. */
  abierto: boolean;
  setAbierto: (abierto: boolean) => void;
  /** Lo que hay escrito (sin debounce). */
  texto: string;
  setTexto: (texto: string) => void;
  filtro: ChatFilter;
  setFiltro: (filtro: ChatFilter) => void;
  /** Hay busqueda o filtro activos: se ven los resultados, no la linea de tiempo. */
  activa: boolean;
  /** El q que se busco (para resaltar), o null. */
  q: string | null;
  /** Identifica la consulta: cambia con q o el filtro. */
  clave: string;
  /** Escrito, pero con menos de 2 caracteres. */
  muyCorto: boolean;
  cargarAnteriores: () => void;
  /** Vuelve a la linea de tiempo normal. */
  salir: () => void;
}

/**
 * Busqueda y filtros del chat (CU23). Los resultados vienen del servidor y
 * viven aca, APARTE de la linea de tiempo del store: los mensajes en vivo
 * siguen entrando a la linea de tiempo y no se inyectan en los resultados.
 *
 * - q con debounce de 300 ms; con menos de 2 caracteres no se pide nada.
 * - Cada consulta nueva invalida la anterior en vuelo (contador `gen`).
 * - "Cargar anteriores" usa beforeSeq = el menor seq de los resultados, con
 *   los mismos q y filtros.
 */
export function useChatSearch(projectId: string, token: string): ChatSearch {
  const [abierto, setAbiertoRaw] = useState(false);
  const [texto, setTexto] = useState("");
  const [filtro, setFiltro] = useState<ChatFilter>("todos");
  const [q, setQ] = useState<string | null>(null);
  const [r, setR] = useState<Resultados>(VACIO);
  const gen = useRef(0);
  const cargandoAnteriores = useRef(false);

  useEffect(() => {
    const t = setTimeout(() => setQ(textoDeBusqueda(texto)), DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [texto]);

  const consulta = useMemo(() => consultaDe(q, filtro), [q, filtro]);
  const clave = consulta ? JSON.stringify(consulta) : "";

  useEffect(() => {
    const g = ++gen.current;
    cargandoAnteriores.current = false;
    if (!consulta) {
      setR(VACIO);
      return;
    }
    setR({ ...VACIO, estado: "loading" });
    fetchChatHistory(projectId, token, { ...consulta, limit: TANDA })
      .then((p) => {
        if (g === gen.current) setR({ ...VACIO, mensajes: p.messages, hasMore: p.hasMore, estado: "ready" });
      })
      .catch((e) => {
        if (g === gen.current) setR({ ...VACIO, estado: "error", error: textoDeErrorDeBusqueda(e) });
      });
  }, [consulta, projectId, token]);

  const cargarAnteriores = useCallback(() => {
    if (!consulta || cargandoAnteriores.current) return;
    if (r.estado !== "ready" || !r.hasMore || r.mensajes.length === 0) return;
    const g = gen.current;
    cargandoAnteriores.current = true;
    setR((prev) => ({ ...prev, olderLoading: true, olderError: null }));
    fetchChatHistory(projectId, token, { ...consulta, beforeSeq: r.mensajes[0].seq, limit: TANDA })
      .then((p) => {
        if (g !== gen.current) return;
        cargandoAnteriores.current = false;
        setR((prev) => ({
          ...prev,
          mensajes: mergeChatMessages(prev.mensajes, p.messages),
          hasMore: p.hasMore && p.messages.length > 0,
          olderLoading: false,
        }));
      })
      .catch((e) => {
        if (g !== gen.current) return;
        cargandoAnteriores.current = false;
        setR((prev) => ({ ...prev, olderLoading: false, olderError: textoDeErrorDeBusqueda(e) }));
      });
  }, [consulta, r, projectId, token]);

  const setAbierto = useCallback((v: boolean) => {
    setAbiertoRaw(v);
    // Al plegar el campo se deja de buscar por texto (el filtro queda).
    if (!v) {
      setTexto("");
      setQ(null);
    }
  }, []);

  const salir = useCallback(() => {
    setAbiertoRaw(false);
    setTexto("");
    setQ(null);
    setFiltro("todos");
  }, []);

  const recortado = texto.trim();
  return {
    ...r,
    abierto,
    setAbierto,
    texto,
    setTexto,
    filtro,
    setFiltro,
    activa: consulta != null,
    q,
    clave,
    muyCorto: recortado.length > 0 && recortado.length < 2,
    cargarAnteriores,
    salir,
  };
}
