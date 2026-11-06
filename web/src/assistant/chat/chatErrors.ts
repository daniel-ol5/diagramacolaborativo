/** Tope del backend para el texto de un mensaje. */
export const MAX_CHARS = 2000;

/**
 * Texto en espanol para un rechazo del chat (/user/queue/errors con
 * destination del chat, o 400/429 del REST).
 */
export function textoDeErrorDelChat(code: string | null | undefined, serverMessage: string | null | undefined): string {
  switch (code) {
    case "NOT_PROJECT_MEMBER":
      return "No eres miembro de este proyecto.";
    case "RATE_LIMITED":
      return "Estás enviando demasiados mensajes; espera un momento.";
    case "INVALID_PAYLOAD":
      return serverMessage?.trim() || "El mensaje no tiene un formato válido.";
    default:
      return serverMessage?.trim() || "No se pudo enviar el mensaje.";
  }
}
