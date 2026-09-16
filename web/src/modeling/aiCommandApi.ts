import { HTTP_BASE } from "../services/config";

/** Espejo de backend/.../ai/dto/AiCommandResponse.java (CU16, §14/RF-02). */
export interface AiCommandResult {
  applied: boolean;
  reason: string | null;
  message: string;
  operations: string[];
  mutationVersion: number | null;
}

/** Los motivos de rechazo documentados en AiCommandResponse.java, para decidir el tono sin leer el texto. */
export const AiCommandReason = {
  GENERAR_DOMINIO: "GENERATE_DOMAIN",
  DEMASIADAS_OPERACIONES: "TOO_MANY_OPERATIONS",
  NO_ENTENDIDO: "NOT_UNDERSTOOD",
  OBJETIVO_INEXISTENTE: "TARGET_NOT_FOUND",
  ROMPERIA_EL_DIAGRAMA: "WOULD_BREAK_DIAGRAM",
  ELEMENTO_BLOQUEADO: "ELEMENT_LOCKED",
  YA_APLICADO: "ALREADY_APPLIED",
  CONTRATO_ROTO: "AI_CONTRACT_VIOLATION",
  IA_CAIDA: "AI_UNAVAILABLE",
} as const;

/**
 * POST /api/v1/diagrams/{id}/ai-command (CU16). El mismo cuerpo (AiCommandResponse) puede
 * venir en dos códigos HTTP distintos, y la diferencia importa para el tono con que se muestra:
 *
 * - 200: aplicado, o un rechazo del guardarraíl/catálogo -- es el asistente respondiendo,
 *   no un fallo. "Créame un sistema para un banco" es 200 con applied:false.
 * - 502: el servicio de IA rompió el contrato o no respondió -- ahí sí hubo un problema real,
 *   aunque el diagrama no se haya tocado.
 *
 * Cualquier otro status (401/403/500) es un fallo de verdad y se descarta como excepción, no
 * como una respuesta del asistente.
 */
export async function sendAiCommand(
  diagramId: string,
  token: string,
  command: string,
  clientCommandId: string,
): Promise<{ httpStatus: number; body: AiCommandResult }> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/ai-command`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ command, clientCommandId }),
  });
  if (res.status !== 200 && res.status !== 502) {
    const text = await res.text().catch(() => "");
    throw new Error(`no se pudo mandar el comando (HTTP ${res.status})${text ? `: ${text.slice(0, 200)}` : ""}`);
  }
  const body = (await res.json()) as AiCommandResult;
  return { httpStatus: res.status, body };
}
