import { useEffect, useId, useRef, type KeyboardEvent } from "react";
import { BUSQUEDA_MAX, FILTROS } from "./chatSearch";
import type { ChatSearch } from "./useChatSearch";
import "./ChatSearchBar.css";

interface Props {
  search: ChatSearch;
}

/**
 * Franja de busqueda y filtros, pegada bajo la cabecera del panel: control
 * segmentado "Todos / Solo IA / Resúmenes" (aria-pressed) y la lupa que
 * despliega el campo de busqueda. Va dentro del contenido del chat (no en
 * AssistantPanel) para que la cabecera quede libre para futuras pestañas.
 */
export function ChatSearchBar({ search }: Props) {
  const ids = useId();
  const idCampo = `${ids}-campo`;
  const idInput = `${ids}-input`;
  const idAyuda = `${ids}-ayuda`;
  const inputRef = useRef<HTMLInputElement | null>(null);
  const lupaRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (search.abierto) inputRef.current?.focus();
  }, [search.abierto]);

  function alTeclear(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== "Escape") return;
    e.preventDefault();
    // Esc: primero borra; con el campo vacio, lo pliega y devuelve el foco a la lupa.
    if (search.texto) {
      search.setTexto("");
    } else {
      search.setAbierto(false);
      lupaRef.current?.focus();
    }
  }

  return (
    <div className="chat-search">
      <div className="chat-search__row">
        <div className="chat-search__filters" role="group" aria-label="Filtrar mensajes">
          {FILTROS.map((f) => (
            <button
              key={f.id}
              type="button"
              className={`chat-search__filter${search.filtro === f.id ? " chat-search__filter--active" : ""}`}
              aria-pressed={search.filtro === f.id}
              onClick={() => search.setFiltro(f.id)}
            >
              {f.etiqueta}
            </button>
          ))}
        </div>
        <button
          ref={lupaRef}
          type="button"
          className={`chat-search__toggle${search.abierto ? " chat-search__toggle--active" : ""}`}
          aria-label="Buscar en el chat"
          aria-expanded={search.abierto}
          aria-controls={idCampo}
          onClick={() => search.setAbierto(!search.abierto)}
        >
          <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
            <circle cx="7" cy="7" r="4.25" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <path d="M10.2 10.2 13.5 13.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        </button>
      </div>

      {search.abierto && (
        <div id={idCampo} className="chat-search__field">
          <label htmlFor={idInput} className="chat-search__label">
            Buscar mensajes
          </label>
          <input
            ref={inputRef}
            id={idInput}
            className="chat-search__input"
            type="search"
            value={search.texto}
            maxLength={BUSQUEDA_MAX}
            placeholder="Buscar en el chat…"
            autoComplete="off"
            aria-describedby={search.muyCorto ? idAyuda : undefined}
            onChange={(e) => search.setTexto(e.target.value)}
            onKeyDown={alTeclear}
          />
          {search.muyCorto && (
            <p id={idAyuda} className="chat-search__hint">
              Escribe al menos 2 caracteres.
            </p>
          )}
        </div>
      )}
    </div>
  );
}
