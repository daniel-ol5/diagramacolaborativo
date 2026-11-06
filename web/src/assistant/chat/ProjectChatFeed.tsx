import { useProjectChatFeed } from "./useProjectChatFeed";

interface Props {
  projectId: string;
  token: string;
  myUserId: string;
}

/**
 * Sin interfaz: mantiene vivo el chat del proyecto (suscripcion, historial,
 * no leidos y marcar leido) mientras DiagramScreen este montado. Va DENTRO
 * de StompConnectionProvider porque usa su conexion. No dibuja nada, por eso
 * no tiene .css.
 */
export function ProjectChatFeed({ projectId, token, myUserId }: Props) {
  useProjectChatFeed(projectId, token, myUserId);
  return null;
}
