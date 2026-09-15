import { HTTP_BASE } from "../services/config";
import type { DiagramSummary, Invitation, InvitationPreview, Member, Project, ProjectRole } from "./types";

/**
 * Error de una llamada REST con el `status` HTTP y el mensaje que manda el
 * servidor (Spring por defecto: `{status, error, message, path}` desde
 * ResponseStatusException). `status` importa en el llamador: un 403 de
 * "no sos miembro" y uno de "proyecto inexistente" son el MISMO caso a
 * proposito (§ProjectService.exigirMembresia) -- no hay forma de
 * distinguirlos ni tiene sentido intentarlo, para no poder averiguar que
 * proyectos existen probando ids.
 */
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function apiFetch<T>(path: string, init: RequestInit = {}, authToken?: string | null): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (init.headers) Object.assign(headers, init.headers as Record<string, string>);
  if (authToken) headers.Authorization = `Bearer ${authToken}`;

  const res = await fetch(`${HTTP_BASE}${path}`, { ...init, headers });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { message?: string };
      if (body.message) message = body.message;
    } catch {
      // el cuerpo no era JSON (o estaba vacio) -- se deja el HTTP status como mensaje
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

// ---------------------------------------------------------------- proyectos

export function listProjects(token: string): Promise<Project[]> {
  return apiFetch<Project[]>("/api/v1/projects", {}, token);
}

export function createProject(token: string, name: string, description: string): Promise<Project> {
  return apiFetch<Project>(
    "/api/v1/projects",
    { method: "POST", body: JSON.stringify({ name, description: description || undefined }) },
    token,
  );
}

// ----------------------------------------------------------------- miembros

export function listMembers(token: string, projectId: string): Promise<Member[]> {
  return apiFetch<Member[]>(`/api/v1/projects/${projectId}/members`, {}, token);
}

export function addMember(
  token: string,
  projectId: string,
  email: string,
  role: ProjectRole,
): Promise<Member> {
  return apiFetch<Member>(
    `/api/v1/projects/${projectId}/members`,
    { method: "POST", body: JSON.stringify({ email, role }) },
    token,
  );
}

// ---------------------------------------------------------------- diagramas

export function listDiagrams(token: string, projectId: string): Promise<DiagramSummary[]> {
  return apiFetch<DiagramSummary[]>(`/api/v1/projects/${projectId}/diagrams`, {}, token);
}

export function createDiagram(token: string, projectId: string, name: string): Promise<DiagramSummary> {
  return apiFetch<DiagramSummary>(
    `/api/v1/projects/${projectId}/diagrams`,
    { method: "POST", body: JSON.stringify({ name }) },
    token,
  );
}

// -------------------------------------------------------------- invitaciones

export function createInvitation(
  token: string,
  projectId: string,
  role: ProjectRole,
  expiresInDays: number | null,
): Promise<Invitation> {
  return apiFetch<Invitation>(
    `/api/v1/projects/${projectId}/invitations`,
    { method: "POST", body: JSON.stringify({ role, expiresInDays: expiresInDays ?? undefined }) },
    token,
  );
}

export function listInvitations(token: string, projectId: string): Promise<Invitation[]> {
  return apiFetch<Invitation[]>(`/api/v1/projects/${projectId}/invitations`, {}, token);
}

export function revokeInvitation(token: string, projectId: string, invitationId: string): Promise<void> {
  return apiFetch<void>(
    `/api/v1/projects/${projectId}/invitations/${invitationId}`,
    { method: "DELETE" },
    token,
  );
}

/** Publico a proposito (InvitationController.mirar): quien recibe un enlace tiene que poder verlo antes de registrarse. */
export function previewInvitation(inviteToken: string): Promise<InvitationPreview> {
  return apiFetch<InvitationPreview>(`/api/v1/invitations/${inviteToken}`);
}

export function acceptInvitation(authToken: string, inviteToken: string): Promise<{ projectId: string }> {
  return apiFetch<{ projectId: string }>(
    `/api/v1/invitations/${inviteToken}/accept`,
    { method: "POST" },
    authToken,
  );
}
