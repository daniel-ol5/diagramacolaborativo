/**
 * Espejo de los DTOs de backend/.../project/dto y backend/.../diagram/dto
 * (P1. Acceso y proyectos, ver docs/puds/02-analisis/01-paquetes.md).
 */

export type ProjectRole = "OWNER" | "EDITOR" | "VIEWER";

/**
 * El rol escrito para una persona, no para la base de datos.
 *
 * OWNER/EDITOR/VIEWER son los valores del CHECK de §13 y viajan asi por la API — pero en
 * pantalla eran la base de datos asomandose en una interfaz que por lo demas esta en castellano.
 * Aparecia crudo en CUATRO lugares: la lista de proyectos, la lista de invitaciones, la de
 * miembros y la pagina de invitacion. En el mismo modal de compartir, el desplegable decia
 * "Editor" y la lista de abajo "EDITOR".
 *
 * Vive aca y no en cada pantalla por el motivo de siempre: escrito cuatro veces, un dia tres
 * dicen una cosa y la cuarta otra.
 */
export const ROL: Record<ProjectRole, string> = {
  OWNER: "Dueño",
  EDITOR: "Editor",
  VIEWER: "Lector",
};

/**
 * El rol tal como se muestra.
 *
 * Tolera que falte —en la vista previa de una invitacion es opcional— y tolera un valor que no
 * conozcamos: prefiere mostrar el crudo antes que romper la pantalla.
 */
export function rolEnCastellano(role?: string): string {
  if (!role) {
    return "";
  }
  return ROL[role as ProjectRole] ?? role;
}

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
