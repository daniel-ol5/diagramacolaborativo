import type { ReactNode } from "react";
import { StompConnectionContext, useStompConnectionCore } from "./stompConnection";

interface Props {
  diagramId: string;
  token: string;
  children: ReactNode;
}

/**
 * Una sola conexion STOMP por (diagramId, token) para todo lo que cuelga de
 * la pantalla del diagrama: el lienzo (useCollaboration) y el chat del
 * proyecto. No dibuja nada, por eso no tiene .css.
 */
export function StompConnectionProvider({ diagramId, token, children }: Props) {
  const connection = useStompConnectionCore(diagramId, token);
  return <StompConnectionContext.Provider value={connection}>{children}</StompConnectionContext.Provider>;
}
