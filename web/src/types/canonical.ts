/**
 * Modelo canonico del diagrama (§7 de docs/PLAN_ARQUITECTONICO_FASE_0.txt).
 *
 * Escrito directamente desde el JSON Schema de §7, no traducido de los records
 * Java de backend/src/main/java/com/modelcollab/metamodel/model/ — ese Java es
 * una implementacion del schema, no el schema en si. Un campo es opcional acá
 * si y solo si §7 no lo lista en su array "required" para ese objeto; un campo
 * tiene un default explicito en el comentario solo cuando §7 declara "default"
 * para el. Ver el mensaje a [planner] por una discrepancia encontrada en
 * ClassEntity.visibility (no resuelta acá, solo documentada).
 */

export type Visibility = "PUBLIC" | "PACKAGE" | "PROTECTED" | "PRIVATE";

export type AttributeType =
  | "INTEGER"
  | "BIGINT"
  | "VARCHAR"
  | "TEXT"
  | "DECIMAL"
  | "BOOLEAN"
  | "DATE"
  | "DATETIME"
  | "UUID";

export type RelationshipType =
  | "ASSOCIATION"
  | "AGGREGATION"
  | "COMPOSITION"
  | "INHERITANCE"
  | "DEPENDENCY"
  | "MANY_TO_MANY";

export type Multiplicity = "0..1" | "1..1" | "0..*" | "1..*";

export type OwningSide = "SOURCE" | "TARGET";

export interface Position {
  x: number;
  y: number;
}

export interface Waypoint {
  x: number;
  y: number;
}

export interface Parameter {
  name: string;
  type: string;
}

export interface Method {
  id: string;
  name: string;
  returnType: string;
  visibility: Visibility;
  parameters?: Parameter[];
}

export interface Attribute {
  id: string;
  name: string;
  type: AttributeType;
  /** Default de §7: 255. */
  length?: number;
  /** Default de §7: 10. */
  precision?: number;
  /** Default de §7: 2. */
  scale?: number;
  visibility: Visibility;
  /** Default de §7: false. */
  isPrimaryKey?: boolean;
  /** Default de §7: true. */
  isNullable?: boolean;
  /** Default de §7: false. */
  isUnique?: boolean;
  defaultValue?: string | null;
}

export interface ClassEntity {
  id: string;
  name: string;
  // DISCREPANCIA §7 vs Java (ver mensaje a [planner]): a diferencia de
  // Attribute.visibility y Method.visibility, §7 NO exige "visibility" en
  // "required" para una clase y no le declara "default" — a diferencia de
  // isAbstract/width/height, que sí lo tienen. El record Java ClassEntity
  // igual normaliza un valor ausente a PUBLIC. Este tipo sigue al schema:
  // opcional, sin default asumido.
  visibility?: Visibility;
  /** Default de §7: false. */
  isAbstract?: boolean;
  position: Position;
  /** Default de §7: 240. */
  width: number;
  /** Default de §7: 180. */
  height: number;
  attributes: Attribute[];
  methods: Method[];
}

export interface Relationship {
  id: string;
  sourceClassId: string;
  targetClassId: string;
  type: RelationshipType;
  /** Default de §7: "SOURCE". */
  owningSide?: OwningSide;
  /** Solo aplica a MANY_TO_MANY; si falta, el generador lo deriva. */
  joinTableName?: string;
  sourceMultiplicity?: Multiplicity;
  targetMultiplicity?: Multiplicity;
  sourceRole?: string;
  targetRole?: string;
  /** Default de §7: true. */
  isNavigable?: boolean;
  waypoints?: Waypoint[];
}

export interface Package {
  id: string;
  name: string;
  classIds: string[];
}

export interface CanonicalModel {
  /** Default de §7: "1.0.0". */
  schemaVersion: string;
  mutationVersion: number;
  packages?: Package[];
  classes: ClassEntity[];
  relationships: Relationship[];
}
