import { useCallback } from "react";
import { fetchChatHistory } from "./chatApi";
import { textoDeErrorDeCarga } from "./chatErrors";
import { useChatStore } from "./chatStore";

/** Tamaño de cada tanda hacia atras. */
const TANDA_ANTERIORES = 50;

/**
 * "Cargar anteriores" de la linea de tiempo normal: GET con beforeSeq = el
 * menor seq cargado; lo que llega se fusiona por seq (queda adelante).
 *
 * Protecciones:
 * - no pide si ya hay una carga en curso (olderStatus "loading"), si no hay
 *   mas viejos o si no hay nada cargado todavia;
 * - si mientras tanto se cambio de proyecto, descarta la respuesta.
 * hasOlder se apaga cuando el servidor dice hasMore=false o devuelve vacio.
 */
export function useLoadOlder(projectId: string, token: string): () => void {
  return useCallback(() => {
    const s = useChatStore.getState();
    if (s.projectId !== projectId || !s.hasOlder || s.olderStatus === "loading") return;
    const menor = s.messages[0]?.seq;
    if (menor == null) return;

    s.setOlder({ olderStatus: "loading", olderError: null });
    fetchChatHistory(projectId, token, { beforeSeq: menor, limit: TANDA_ANTERIORES })
      .then((pagina) => {
        const x = useChatStore.getState();
        if (x.projectId !== projectId) return;
        x.mergeMessages(pagina.messages);
        x.setOlder({ olderStatus: "idle", hasOlder: pagina.hasMore && pagina.messages.length > 0 });
      })
      .catch((e) => {
        if (useChatStore.getState().projectId !== projectId) return;
        useChatStore.getState().setOlder({
          olderStatus: "error",
          olderError: textoDeErrorDeCarga(e, "No se pudieron cargar los mensajes anteriores. Revisa tu conexión."),
        });
      });
  }, [projectId, token]);
}
