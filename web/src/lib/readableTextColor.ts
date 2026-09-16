/**
 * Los colores de colaborador los manda el servidor (Participante.color) y
 * estan medidos como TRAZO -- bordes de lock, punto de cursor, contra la
 * tinta del lienzo (D-XX). Usarlos como RELLENO con texto encima (el chip
 * del roster, la etiqueta del cursor) es un uso distinto que esa medicion
 * nunca cubrio: con texto blanco fijo, un color claro de la paleta puede
 * no llegar a 4.5:1 (confirmado con web/scripts/check-rendered-contrast.mjs).
 *
 * La solucion es de este lado, no tocando la paleta: elegir negro o blanco
 * segun cual contraste mas contra CADA color puntual. Sobrevive a que la
 * paleta cambie mañana, y funciona para los ocho colores, no solo para el
 * que fallaba.
 */

function luminance([r, g, b]: [number, number, number]): number {
  const f = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function contrastRatio(a: number, b: number): number {
  const [hi, lo] = a > b ? [a, b] : [b, a];
  return (hi + 0.05) / (lo + 0.05);
}

function parseColor(color: string): [number, number, number] | null {
  const hex = color.trim().match(/^#?([0-9a-fA-F]{6})$/);
  if (hex) {
    const n = hex[1];
    return [parseInt(n.slice(0, 2), 16), parseInt(n.slice(2, 4), 16), parseInt(n.slice(4, 6), 16)];
  }
  const rgb = color.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
  if (rgb) return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];
  return null;
}

const BLANCO: [number, number, number] = [255, 255, 255];
const TINTA: [number, number, number] = [0x1a, 0x1a, 0x2e]; // --color-text / --color-border-strong

/** Blanco o la tinta oscura del lienzo, el que contraste mas contra `background`. */
export function readableTextColor(background: string): string {
  const rgb = parseColor(background);
  if (!rgb) return "#ffffff";
  const lBg = luminance(rgb);
  const ratioBlanco = contrastRatio(lBg, luminance(BLANCO));
  const ratioTinta = contrastRatio(lBg, luminance(TINTA));
  return ratioBlanco >= ratioTinta ? "#ffffff" : "#1a1a2e";
}
