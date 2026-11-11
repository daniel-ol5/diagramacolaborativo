import { useEffect, useMemo, useRef, useState } from "react";
import { useCollaborationStore } from "../../store/collaborationStore";
import { useChatStore } from "./chatStore";
import { ChatInput } from "./ChatInput";
import { ChatSearchBar } from "./ChatSearchBar";
import { textoDeResultados } from "./chatSearch";
import { MessageList, type ListPosition } from "./MessageList";
import { idDelPrimerNoLeido } from "./unread";
import { TypingIndicator } from "./TypingIndicator";
import { useChatSearch } from "./useChatSearch";
import { useProjectChat } from "./useProjectChat";
import { useLoadOlder } from "./useLoadOlder";
import "./ChatPanel.css";

interface Props {
  projectId: string;
  /** El diagrama abierto: viaja en cada mensaje como contexto. */
  diagramId: string | null;
  token: string;
  myUserId: string;
  myName: string;
  onImportPhoto?: () => void;
}

/**
 * Contenido de la pestaña "Chat IA" del panel del asistente: busqueda y
 * filtros, historial y mensajes en vivo, indicador de la IA y campo de
 * envio. Los datos los mantiene ProjectChatFeed aunque el panel este cerrado.
 *
 * Con busqueda o filtro activos se ven los resultados del servidor en lugar
 * de la linea de tiempo; la linea de tiempo sigue recibiendo en vivo y, al
 * salir de la busqueda, vuelve a la posicion en que estaba.
 */
export function ChatPanel({ projectId, diagramId, token, myUserId, myName, onImportPhoto }: Props) {
  const { send, retry } = useProjectChat(projectId, diagramId);
  const loadOlder = useLoadOlder(projectId, token);
  const search = useChatSearch(projectId, token);
  /** Donde estaba la linea de tiempo al entrar a la busqueda. */
  const posicionLinea = useRef<ListPosition | null>(null);

  // Panel abierto: cuenta para "marcar leido" y para no sumar no leidos.
  // Si la lista esta al final lo informa MessageList al montarse (al final, o
  // en la linea "Mensajes nuevos"); sin lista, el efecto de mas abajo.
  useEffect(() => {
    const s = useChatStore.getState();
    s.setPanelOpen(true);
    return () => useChatStore.getState().setPanelOpen(false);
  }, []);

  // Linea "Mensajes nuevos": el lastReadSeq que se tenia AL ABRIR, solo si
  // habia no leidos. Se fija una vez y dura mientras el panel siga abierto
  // (al cerrarlo se desmonta y desaparece). Si al abrir todavia no habia
  // respondido GET /chat/read, se fija cuando responde (antes de eso no se
  // marca leido, asi que el valor sigue siendo el "de al abrir").
  const [corte, setCorte] = useState<number | null>(() => {
    const s = useChatStore.getState();
    return s.projectId === projectId && s.lastReadSeq != null && s.unreadCount > 0 ? s.lastReadSeq : null;
  });
  const corteDecidido = useRef(useChatStore.getState().lastReadSeq != null);
  const lastReadSeq = useChatStore((s) => s.lastReadSeq);
  useEffect(() => {
    if (corteDecidido.current || lastReadSeq == null) return;
    corteDecidido.current = true;
    if (useChatStore.getState().unreadCount > 0) setCorte(lastReadSeq);
  }, [lastReadSeq]);

  const deEsteProyecto = useChatStore((s) => s.projectId === projectId);
  const messages = useChatStore((s) => s.messages);
  const outbox = useChatStore((s) => s.outbox);
  const loadStatus = useChatStore((s) => s.loadStatus);
  const error = useChatStore((s) => s.error);
  const sendError = useChatStore((s) => s.sendError);
  const connected = useChatStore((s) => s.connected);
  const aiTyping = useChatStore((s) => s.aiTyping);
  const hasOlder = useChatStore((s) => s.hasOlder);
  const olderStatus = useChatStore((s) => s.olderStatus);
  const olderError = useChatStore((s) => s.olderError);
  // Mi color es el de la presencia: el mismo que el servidor pone en authorColor.
  const myColor = useCollaborationStore((s) => s.roster.find((p) => p.userId === myUserId)?.color);

  const visibles = deEsteProyecto ? messages : [];
  const pendientes = deEsteProyecto ? outbox : [];
  const hayAlgo = visibles.length > 0 || pendientes.length > 0;
  const me = { userId: myUserId, name: myName, color: myColor };
  const idLineaNuevos = useMemo(() => idDelPrimerNoLeido(visibles, corte, myUserId), [visibles, corte, myUserId]);

  // Mientras se ven resultados, el final de la linea de tiempo no se esta
  // viendo: los mensajes en vivo cuentan como no leidos y no se marca leido.
  // Al volver, la linea de tiempo restaurada informa donde quedo (o, si esta
  // vacia, se considera al final).
  useEffect(() => {
    if (search.activa) useChatStore.getState().setAtBottom(false);
    else if (!hayAlgo) useChatStore.getState().setAtBottom(true);
  }, [search.activa, hayAlgo]);

  function vistaDeResultados() {
    const n = search.mensajes.length;
    return (
      <>
        <div className="chat-panel__results-bar">
          <p className="chat-panel__results-count" role="status">
            {search.estado === "loading"
              ? "Buscando…"
              : search.estado === "ready"
                ? textoDeResultados(n, search.hasMore)
                : ""}
          </p>
          <button type="button" className="chat-panel__exit-search" onClick={search.salir}>
            Salir de la búsqueda
          </button>
        </div>
        {search.estado === "error" && search.error && (
          <p className="chat-panel__error chat-panel__error--top" role="alert">
            {search.error}
          </p>
        )}
        {n > 0 ? (
          <MessageList
            // Una consulta nueva es una lista nueva (empieza al final).
            key={search.clave}
            label="Resultados de la búsqueda"
            messages={search.mensajes}
            pending={[]}
            me={me}
            onRetry={() => {}}
            retryDisabled
            hasOlder={search.hasMore}
            loadingOlder={search.olderLoading}
            olderError={search.olderError}
            onLoadOlder={search.cargarAnteriores}
            trackReadPosition={false}
            highlight={search.q}
            diagramId={diagramId}
            token={token}
          />
        ) : (
          <div className="chat-panel__empty" />
        )}
      </>
    );
  }

  function lineaDeTiempo() {
    return hayAlgo ? (
      <MessageList
        messages={visibles}
        pending={pendientes}
        me={me}
        onRetry={retry}
        retryDisabled={!connected}
        hasOlder={deEsteProyecto && hasOlder}
        loadingOlder={olderStatus === "loading"}
        olderError={deEsteProyecto ? olderError : null}
        onLoadOlder={loadOlder}
        newMarkerId={idLineaNuevos}
        initialPosition={posicionLinea.current}
        onLeave={(p) => {
          posicionLinea.current = p;
        }}
        diagramId={diagramId}
        token={token}
      />
    ) : (
      <div className="chat-panel__empty">
        {loadStatus === "idle" ? (
          <p className="chat-panel__hint">Conectando con el chat…</p>
        ) : loadStatus === "loading" ? (
          <p className="chat-panel__hint">Cargando mensajes…</p>
        ) : loadStatus === "ready" ? (
          <p className="chat-panel__hint">
            Todavía no hay mensajes. Todos los miembros del proyecto verán lo que se escriba aquí. Escribe @IA
            para preguntarle a la IA.
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <div className="chat-panel">
      <ChatSearchBar search={search} />
      {search.activa ? vistaDeResultados() : lineaDeTiempo()}
      {!search.activa && error && (
        <p className="chat-panel__error" role="alert">
          {error}
        </p>
      )}
      <TypingIndicator forUserNames={deEsteProyecto ? Object.values(aiTyping) : []} />
      <ChatInput connected={connected} error={sendError} onSend={send} onImportPhoto={onImportPhoto} />
    </div>
  );
}
