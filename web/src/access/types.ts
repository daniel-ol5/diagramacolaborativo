/**
 * Espejo de los DTOs de backend/.../project/dto y backend/.../diagram/dto
 * (P1. Acceso y proyectos, ver docs/puds/02-analisis/01-paquetes.md).
 */

export type ProjectRole = "OWNER" | "EDITOR" | "VIEWER";

/** ProjectResponse.java. `role` es el rol de QUIEN PREGUNTA en este proyecto, no un atributo del proyecto. */
export interface Project {
  id: string;
  name: string;
  description: string | null;
  ownerId: string;
  role: ProjectRole;
  createdAt: string;
}

/** DiagramResponse.java. Sin el contenido -- para eso esta fetchDiagramSnapshot(). */
export interface DiagramSummary {
  id: string;
  projectId: string;
  name: string;
  mutationVersion: number;
  updatedAt: string;
}

/** MemberResponse.java. */
export interface Member {
  userId: string;
  email: string;
  fullName: string;
  role: ProjectRole;
}

/** InvitationResponse.java. `url` ya viene armada (ruta /invite/{token}) -- nunca se construye del lado del cliente. */
export interface Invitation {
  id: string;
  token: string;
  url: string;
  role: ProjectRole;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
  active: boolean;
}

/**
 * InvitationPreview.java. El mapper usa NON_NULL: con token invalido,
 * `projectName`/`role` no viajan como null -- NO VIAJAN. Por eso son
 * opcionales (`?`), no nulables (`| null`): un `body.role` con token
 * invalido es `undefined`, no `null`.
 */
export interface InvitationPreview {
  valid: boolean;
  reason?: "EXPIRED" | "REVOKED" | "NOT_FOUND";
  projectName?: string;
  role?: ProjectRole;
}
