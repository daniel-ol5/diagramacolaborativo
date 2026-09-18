import { HTTP_BASE } from "../services/config";

/**
 * Espejo de backend/.../generator/dto/{SchemaPreview,SchemaTable,SchemaColumn}.java.
 *
 * El esquema ya viene ENSAMBLADO: las columnas FK ya estan calculadas y del
 * lado correcto (owningSide), la herencia JOINED ya resuelta (una columna
 * puede ser PK y FK a la vez -- no asumir que son excluyentes), las tablas
 * intermedias de MANY_TO_MANY ya armadas. Nada de esto se recalcula aca:
 * "vos dibujas, el servidor calcula" -- es el mismo modelo que renderizan
 * las plantillas del generador, no un calculo paralelo.
 *
 * Los campos ausentes NO viajan como null: directamente no estan en el
 * objeto (de ahi que sean opcionales, no `T | null`).
 */
export interface SchemaColumn {
  name: string;
  /** true = palabra reservada de SQL, hay que citarla (§11.2.1). */
  quoted: boolean;
  type: string;
  length?: number;
  precision?: number;
  scale?: number;
  primaryKey: boolean;
  generated: boolean;
  nullable: boolean;
  unique: boolean;
  /** Nombre FISICO (SchemaTable.name) de la tabla referenciada -- ausente si esta columna no es FK. */
  references?: string;
}

export interface SchemaTable {
  /** Ausente si es tabla intermedia de un MANY_TO_MANY. */
  className?: string;
  name: string;
  quoted: boolean;
  joinTable: boolean;
  /** Tiene tabla (estrategia JOINED) pero no API REST propia. */
  abstractClass: boolean;
  columns: SchemaColumn[];
}

export interface SchemaPreview {
  mutationVersion: number;
  tables: SchemaTable[];
}

/** GET /api/v1/diagrams/{id}/schema-preview (CU15). Lectura, cualquier miembro (VIEWER incluido). */
export async function fetchSchemaPreview(diagramId: string, token: string): Promise<SchemaPreview> {
  const res = await fetch(`${HTTP_BASE}/api/v1/diagrams/${diagramId}/schema-preview`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`no se pudo obtener el esquema (HTTP ${res.status})${text ? `: ${text.slice(0, 200)}` : ""}`);
  }
  return res.json();
}

/** "order" -> `"order"`, cuando hace falta citarla -- lo que explica por que esa tabla se escribe asi en cualquier consulta (§11.2.1). */
export function displayName(name: string, quoted: boolean): string {
  return quoted ? `"${name}"` : name;
}
