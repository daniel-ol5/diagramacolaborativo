import { useCollaborationStore } from "../../store/collaborationStore";
import { useChatStore } from "./chatStore";
import { ChatInput } from "./ChatInput";
import { MessageList } from "./MessageList";
import { TypingIndicator } from "./TypingIndicator";
import { useProjectChat } from "./useProjectChat";
import "./ChatPanel.css";

interface Props {
  projectId: string;
  /** El diagrama abierto: viaja en cada mensaje como contexto. */
  diagramId: string | null;
  token: string;
  myUserId: string;
  myName: string;
}

/**
 * Contenido de la pestaña "Chat IA" del panel del asistente: historial y
 * mensajes en vivo, indicador de la IA y campo de envio.
 */
export function ChatPanel({ projectId, diagramId, token, myUserId, myName }: Props) {
  const { send, retry } = useProjectChat(projectId, token, diagramId);

  const deEsteProyecto = useChatStore((s) => s.projectId === projectId);
  const messages = useChatStore((s) => s.messages);
  const outbox = useChatStore((s) => s.outbox);
  const loadStatus = useChatStore((s) => s.loadStatus);
  const error = useChatStore((s) => s.error);
  const sendError = useChatStore((s) => s.sendError);
  const connected = useChatStore((s) => s.connected);
  const aiTyping = useChatStore((s) => s.aiTyping);
  // Mi color es el de la presencia: el mismo que el servidor pone en authorColor.
  const myColor = useCollaborationStore((s) => s.roster.find((p) => p.userId === myUserId)?.color);

  const visibles = deEsteProyecto ? messages : [];
  const pendientes = deEsteProyecto ? outbox : [];
  const hayAlgo = visibles.length > 0 || pendientes.length > 0;

  return (
    <div className="chat-panel">
      {hayAlgo ? (
        <MessageList
          messages={visibles}
          pending={pendientes}
          me={{ userId: myUserId, name: myName, color: myColor }}
          onRetry={retry}
          retryDisabled={!connected}
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
      )}
      {error && (
        <p className="chat-panel__error" role="alert">
          {error}
        </p>
      )}
      <TypingIndicator forUserNames={deEsteProyecto ? Object.values(aiTyping) : []} />
      <ChatInput connected={connected} error={sendError} onSend={send} />
    </div>
  );
}
