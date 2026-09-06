export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

export function centerOf(box: Box): Point {
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

/**
 * Punto donde el segmento (centro de `box` -> `toward`) cruza el borde del
 * rectangulo. Estandar para que las aristas toquen el borde de la caja UML
 * en vez de terminar en su centro (donde quedarian tapadas por el nodo).
 */
export function rectBoundaryPoint(box: Box, toward: Point): Point {
  const c = centerOf(box);
  const dx = toward.x - c.x;
  const dy = toward.y - c.y;
  if (dx === 0 && dy === 0) return c;

  const halfW = box.width / 2;
  const halfH = box.height / 2;
  const tx = dx !== 0 ? halfW / Math.abs(dx) : Infinity;
  const ty = dy !== 0 ? halfH / Math.abs(dy) : Infinity;
  const t = Math.min(tx, ty);

  return { x: c.x + dx * t, y: c.y + dy * t };
}

/** Punto a `distance` de `from`, en direccion de `towards` (para etiquetas cerca de un extremo). */
export function offsetToward(from: Point, towards: Point, distance: number): Point {
  const dx = towards.x - from.x;
  const dy = towards.y - from.y;
  const length = Math.hypot(dx, dy) || 1;
  return { x: from.x + (dx / length) * distance, y: from.y + (dy / length) * distance };
}
