import { HTTP_BASE } from "../../services/config";
import type { AssistantSettingsResponse, AssistantSettingsUpdateRequest } from "./types";

export class SettingsApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "SettingsApiError";
  }
}

export async function fetchAssistantSettings(
  projectId: string,
  token: string,
): Promise<AssistantSettingsResponse> {
  const res = await fetch(`${HTTP_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/assistant-settings`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/json",
    },
  });

  if (!res.ok) {
    throw new SettingsApiError(res.status, `Error al cargar la configuración (${res.status})`);
  }

  return (await res.json()) as AssistantSettingsResponse;
}

export async function updateAssistantSettings(
  projectId: string,
  token: string,
  data: AssistantSettingsUpdateRequest,
): Promise<AssistantSettingsResponse> {
  const res = await fetch(`${HTTP_BASE}/api/v1/projects/${encodeURIComponent(projectId)}/assistant-settings`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(data),
  });

  if (!res.ok) {
    let mensaje = `Error al guardar la configuración (${res.status})`;
    try {
      const err = await res.json();
      if (err.message) mensaje = err.message;
    } catch {
      // Ignorar si no viene cuerpo json
    }
    throw new SettingsApiError(res.status, mensaje);
  }

  return (await res.json()) as AssistantSettingsResponse;
}
