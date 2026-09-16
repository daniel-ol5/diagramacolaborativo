// ModelCollab :: verifica que los tokens de color de web/src/styles/tokens.css
// cumplan WCAG AA (4.5:1 texto, 3:1 bordes/iconos y trazos).
//
// Por la misma regla que ordena evidencias/README.md: "cuando existe una
// verificacion automatica, esa es la prueba y la imagen es lo que se
// muestra en pantalla". Una captura no prueba un numero -- esto si, y falla
// si alguien baja un token por debajo del piso sin darse cuenta.
//
// Uso: node scripts/check-contrast.mjs

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const tokensPath = join(__dirname, "..", "web", "src", "styles", "tokens.css");
const css = readFileSync(tokensPath, "utf8");

// Resuelve el valor de un token, siguiendo una cadena de var(--otro-token)
// cuando el valor no es un hex literal (p. ej. --canvas-edge-stroke-active
// esta definido como var(--color-primary) a proposito, para no duplicar el
// color de marca).
function tokenHex(name, seen = new Set()) {
  if (seen.has(name)) throw new Error(`referencia circular en --${name}`);
  seen.add(name);
  const re = new RegExp(`--${name}:\\s*([^;]+);`);
  const m = css.match(re);
  if (!m) throw new Error(`token --${name} no encontrado en ${tokensPath}`);
  const value = m[1].trim();
  const hexMatch = value.match(/^#[0-9a-fA-F]{3,8}$/);
  if (hexMatch) return hexMatch[0];
  const varMatch = value.match(/^var\(--([\w-]+)\)$/);
  if (varMatch) return tokenHex(varMatch[1], seen);
  throw new Error(`--${name} no es un color resoluble: "${value}"`);
}

function luminance(hex) {
  hex = hex.replace("#", "");
  if (hex.length === 3) hex = hex.split("").map((c) => c + c).join("");
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const f = (c) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function ratio(hexA, hexB) {
  const [hi, lo] = [luminance(hexA), luminance(hexB)].sort((a, b) => b - a);
  return (hi + 0.05) / (lo + 0.05);
}

// Distancia perceptual (CIE76 deltaE) entre dos colores, no razon de
// contraste WCAG: "se distinguen entre si" es una pregunta de hue/croma,
// no de luminancia sola -- dos colores pueden tener luminancia parecida
// (bajo razon de contraste entre ellos) y ser igual de distinguibles si
// difieren en tono, que es exactamente el caso de un gris vs un azul de
// marca. Mismo criterio que ya se uso para medir la paleta de
// colaboradores (umbral 25 alla).
function deltaE76(hexA, hexB) {
  const toLab = (hex) => {
    hex = hex.replace("#", "");
    if (hex.length === 3) hex = hex.split("").map((c) => c + c).join("");
    let [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
    const inv = (c) => (c > 0.04045 ? Math.pow((c + 0.055) / 1.055, 2.4) : c / 12.92);
    [r, g, b] = [inv(r), inv(g), inv(b)];
    let x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047;
    let y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.0;
    let z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883;
    const f = (t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
    [x, y, z] = [f(x), f(y), f(z)];
    return [116 * y - 16, 500 * (x - y), 200 * (y - z)];
  };
  const [L1, a1, b1] = toLab(hexA);
  const [L2, a2, b2] = toLab(hexB);
  return Math.sqrt((L1 - L2) ** 2 + (a1 - a2) ** 2 + (b1 - b2) ** 2);
}

// [descripcion, tokenFondo, tokenFrente, umbral, categoria]
const checks = [
  ["borde/chrome sobre fondo", "color-bg", "color-border", 3, "borde"],
  ["borde/chrome sobre pantalla standalone", "color-bg-page", "color-border", 3, "borde"],
  ["borde fuerte (clase UML) sobre fondo", "color-bg", "color-border-strong", 3, "borde"],
  ["texto principal sobre fondo", "color-bg", "color-text", 4.5, "texto"],
  ["texto secundario sobre fondo", "color-bg", "color-text-secondary", 4.5, "texto"],
  ["texto secundario sobre superficie de seleccion", "color-bg-selected", "color-text-secondary", 4.5, "texto"],
  ["texto terciario (piso) sobre fondo", "color-bg", "color-text-tertiary", 4.5, "texto"],
  ["link/acento sobre fondo", "color-bg", "color-link", 4.5, "texto"],
  ["link/acento sobre pantalla standalone", "color-bg-page", "color-link", 4.5, "texto"],
  ["texto de contraste sobre boton primario", "color-primary", "color-primary-contrast", 4.5, "texto"],
  ["texto blanco sobre boton deshabilitado", "color-primary-muted", "color-text-on-dark", 4.5, "texto"],
  ["texto blanco sobre advertencia (badge offline)", "color-warning", "color-text-on-dark", 4.5, "texto"],
  ["danger sobre fondo", "color-bg", "color-danger", 4.5, "texto"],
  ["success sobre fondo", "color-bg", "color-success", 4.5, "texto"],
  ["warning sobre su propio fondo (respuesta fallida de IA)", "color-warning-bg", "color-warning", 4.5, "texto"],
  ["borde sobre el lienzo (plano distinto de --color-bg)", "canvas-bg", "color-border", 3, "borde"],
  ["trazo de conector en reposo sobre el lienzo", "canvas-bg", "canvas-edge-stroke", 3, "borde"],
  ["trazo de conector activo/seleccionado sobre el lienzo", "canvas-bg", "canvas-edge-stroke-active", 3, "borde"],
];

console.log(`Verificando contraste WCAG AA contra ${tokensPath}\n`);
let failed = 0;
for (const [desc, bgName, fgName, threshold, categoria] of checks) {
  const bg = tokenHex(bgName);
  const fg = tokenHex(fgName);
  const r = ratio(bg, fg);
  const ok = r >= threshold;
  if (!ok) failed++;
  console.log(
    `${ok ? "PASA" : "FALLA"}  ${desc.padEnd(48)} ${r.toFixed(2)}:1  (piso ${categoria}: ${threshold}:1)`,
  );
}

// El seleccionado tiene que distinguirse del reposo, no solo pasar cada uno
// por separado -- si ambos subieran de contraste convergiendo al mismo
// gris oscuro, "seleccionado" dejaria de leerse aunque los dos pasen WCAG.
// Es una pregunta de tono, no de luminancia: se mide con deltaE (mismo
// criterio que la paleta de colaboradores), no con razon de contraste.
const reposo = tokenHex("canvas-edge-stroke");
const activo = tokenHex("canvas-edge-stroke-active");
const distincion = deltaE76(reposo, activo);
const distincionOk = distincion >= 25; // mismo umbral usado para la paleta de colaboradores
console.log(
  `${distincionOk ? "PASA" : "FALLA"}  reposo se distingue de seleccionado (deltaE, no solo cada uno vs fondo) ΔE=${distincion.toFixed(1)}`,
);
if (!distincionOk) failed++;

console.log(`\n${checks.length + 1 - failed}/${checks.length + 1} OK`);
process.exit(failed === 0 ? 0 : 1);
