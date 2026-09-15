// ModelCollab :: mide el contraste de CADA texto contra su fondo REAL ya
// renderizado, y detecta cajas de texto que se pisan entre si.
//
// check-contrast.mjs mide pares de tokens ("--color-text sobre --color-bg
// da tanto") y eso no alcanza: no sabe sobre que esta pintado un texto de
// verdad, ni si dos elementos terminan superpuestos por un problema de
// maquetado (no de color). Un texto puede pasar los 18 checks de tokens y
// seguir siendo ilegible si cae sobre un fondo que nadie emparejo, o
// invisible si otro elemento se le monta encima.
//
// Este script carga cada pantalla en un Chrome real (headless), con datos
// que fuerzan los casos de borde de una lista (nombre largo, sin
// descripcion, lista vacia, lista de varios) y para cada nodo de texto:
//   1. Lee su color computado y camina hacia arriba hasta encontrar un
//      fondo no transparente -- el fondo EFECTIVO, no el token que alguien
//      penso que iba debajo.
//   2. Calcula la razon de contraste WCAG real entre ese color y ese fondo.
//   3. Guarda su rectangulo en pantalla.
// Al final, compara todos los rectangulos de texto entre si (excluyendo
// pares donde uno contiene al otro, que es la jerarquia normal del DOM) y
// reporta cualquier par que se superponga en un area no trivial.
//
// Requiere el backend en :8080 y el front (vite dev) en :5173, y las
// credenciales de seed-dev.sql. Vive en web/scripts/ (no en scripts/ del
// repo) porque necesita puppeteer-core, que es una dependencia de web/, no
// del repo entero -- Node resuelve node_modules desde donde vive el
// archivo, no desde el directorio actual.
//
// Uso: cd web && node scripts/check-rendered-contrast.mjs

import puppeteer from "puppeteer-core";

const CHROME =
  process.env.CHROME_PATH ?? "C:/Program Files/Google/Chrome/Application/chrome.exe";
const APP_URL = "http://localhost:5173/";

let fallas = 0;
function reportar(ok, msg) {
  console.log(`${ok ? "PASA" : "FALLA"}  ${msg}`);
  if (!ok) fallas++;
}

function luminance(rgb) {
  const f = (c) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  const [r, g, b] = rgb.map((c) => f(c / 255));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function ratio(rgbA, rgbB) {
  const [hi, lo] = [luminance(rgbA), luminance(rgbB)].sort((a, b) => b - a);
  return (hi + 0.05) / (lo + 0.05);
}
function parseRgb(str) {
  const m = str.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
  if (!m) return null;
  return { rgb: [Number(m[1]), Number(m[2]), Number(m[3])], alpha: m[4] === undefined ? 1 : Number(m[4]) };
}

/** Corre DENTRO de la pagina: recolecta cada nodo de texto visible con su color, fondo efectivo y rectangulo. */
function recolectarEnPagina() {
  function fondoEfectivo(el) {
    let node = el;
    while (node) {
      const cs = getComputedStyle(node);
      const bg = cs.backgroundColor;
      const m = bg.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
      if (m) {
        const alpha = m[4] === undefined ? 1 : Number(m[4]);
        if (alpha > 0.5) return [Number(m[1]), Number(m[2]), Number(m[3])];
      }
      node = node.parentElement;
    }
    return [255, 255, 255]; // el body no tiene fondo propio en este proyecto -- blanco real del viewport
  }

  const resultados = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let nodo;
  while ((nodo = walker.nextNode())) {
    const texto = nodo.textContent.trim();
    if (!texto) continue;
    const el = nodo.parentElement;
    if (!el) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === "hidden" || cs.display === "none" || Number(cs.opacity) === 0) continue;

    const range = document.createRange();
    range.selectNodeContents(nodo);
    const rects = [...range.getClientRects()].filter((r) => r.width > 0 && r.height > 0);
    if (rects.length === 0) continue;

    resultados.push({
      texto: texto.slice(0, 60),
      selector: el.className ? `${el.tagName.toLowerCase()}.${String(el.className).split(" ").join(".")}` : el.tagName.toLowerCase(),
      color: cs.color,
      fontSizePx: parseFloat(cs.fontSize),
      rects: rects.map((r) => ({ x: r.x, y: r.y, width: r.width, height: r.height, top: r.top, left: r.left, right: r.right, bottom: r.bottom })),
      fondoEfectivo: fondoEfectivo(el),
    });
  }
  return resultados;
}

function rectanglesOverlapArea(a, b) {
  const x = Math.max(a.left, b.left);
  const y = Math.max(a.top, b.top);
  const right = Math.min(a.right, b.right);
  const bottom = Math.min(a.bottom, b.bottom);
  if (right <= x || bottom <= y) return 0;
  return (right - x) * (bottom - y);
}

async function evaluarPantalla(page, nombre) {
  console.log(`\n--- ${nombre} ---`);
  const nodos = await page.evaluate(recolectarEnPagina);

  for (const n of nodos) {
    const fg = parseRgb(n.color);
    if (!fg) continue;
    const r = ratio(fg.rgb, n.fondoEfectivo);
    const piso = 4.5;
    const ok = r >= piso;
    if (!ok) {
      reportar(false, `contraste ${r.toFixed(2)}:1 (< ${piso}) -- "${n.texto}" [${n.selector}] color=rgb(${n.color}) sobre fondo efectivo rgb(${n.fondoEfectivo.join(",")})`);
    }
  }

  // Superposicion: comparo cada par de nodos de texto DISTINTOS. El primer
  // intento comparaba area absoluta (> 20px^2) y encontraba falsos
  // positivos: un span inline dentro de un h1 que envuelve a varias lineas
  // produce un rect por LINEA (getClientRects), y dos rects de textos
  // vecinos que se cortan justo en el punto de ajuste de linea rozan un
  // puñado de pixeles por el colapso de espacios en blanco -- no es una
  // superposicion real, es como el navegador mide texto que fluye. Exigir
  // que el area de solape sea una fraccion grande del rect MAS CHICO (no
  // un numero de pixeles fijo) filtra ese ruido y sigue detectando el caso
  // real: dos cajas de texto puestas una sobre la otra.
  const rects = nodos.flatMap((n, i) => n.rects.map((r) => ({ ...r, i, texto: n.texto, selector: n.selector })));
  let solapes = 0;
  const UMBRAL_FRACCION = 0.25;
  for (let a = 0; a < rects.length; a++) {
    for (let b = a + 1; b < rects.length; b++) {
      if (rects[a].i === rects[b].i) continue;
      const area = rectanglesOverlapArea(rects[a], rects[b]);
      if (area === 0) continue;
      const areaMenor = Math.min(rects[a].width * rects[a].height, rects[b].width * rects[b].height);
      if (areaMenor > 0 && area / areaMenor >= UMBRAL_FRACCION) {
        solapes++;
        reportar(false, `se superponen (${area.toFixed(0)}px², ${((area / areaMenor) * 100).toFixed(0)}% del rect menor): "${rects[a].texto}" [${rects[a].selector}] <-> "${rects[b].texto}" [${rects[b].selector}]`);
      }
    }
  }
  if (solapes === 0) reportar(true, `sin superposiciones de texto (${rects.length} rectangulos comparados)`);
  if (nodos.every((n) => { const fg = parseRgb(n.color); return fg && ratio(fg.rgb, n.fondoEfectivo) >= 4.5; })) {
    reportar(true, `los ${nodos.length} nodos de texto pasan 4.5:1 contra su fondo real`);
  }
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: "new", args: ["--no-sandbox", "--window-size=1400,900"] });
const page = await browser.newPage();
await page.setViewport({ width: 1400, height: 900 });

async function login() {
  await page.goto(APP_URL, { waitUntil: "networkidle0" });
  await page.type('input[type="email"]', "owner@modelcollab.dev");
  await page.type('input[type="password"]', "Modelcollab1!");
  await page.click('button[type="submit"]');
  await page.waitForSelector(".access-page", { timeout: 8000 });
}

await login();
await new Promise((r) => setTimeout(r, 500));
await evaluarPantalla(page, "Pantalla de proyectos (lista real, con nombres variados)");

// Un proyecto con nombre largo y descripcion larga, para forzar el caso de borde.
const NOMBRE_LARGO = "Sistema Integral de Gestion de Turnos y Facturacion para Barberias Multisucursal " + Date.now();
await page.click(".access-page__primary-btn");
await page.waitForSelector("#new-project-name", { timeout: 5000 });
await page.type("#new-project-name", NOMBRE_LARGO);
await page.type("#new-project-description", "Una descripcion tambien bastante larga, para ver si se superpone con el nombre o con el rol en la misma tarjeta de la lista.");
await page.click(".modal__primary");
await page.waitForFunction(() => /^\/projects\/[^/]+$/.test(location.pathname), { timeout: 8000 });
await new Promise((r) => setTimeout(r, 500));
await evaluarPantalla(page, "Proyecto vacio, nombre largo (sin diagramas todavia)");

const NOMBRE_DIAGRAMA_LARGO = "Diagrama de Dominio Completo con Todas las Entidades del Sistema " + Date.now();
await page.click(".access-page__primary-btn");
await page.waitForSelector("#new-diagram-name", { timeout: 5000 });
await page.type("#new-diagram-name", NOMBRE_DIAGRAMA_LARGO);
await page.click(".modal__primary");
await page.waitForSelector(".react-flow", { timeout: 8000 });
await new Promise((r) => setTimeout(r, 800));
await evaluarPantalla(page, "Lienzo con la barra nueva (nombre de diagrama largo)");

const back = await page.evaluateHandle(() => [...document.querySelectorAll("a")].find((a) => a.textContent?.includes("Proyecto")));
await back.asElement()?.click();
await page.waitForFunction(() => [...document.querySelectorAll("button")].some((b) => b.textContent === "Compartir"), { timeout: 8000 });
await new Promise((r) => setTimeout(r, 500));
await evaluarPantalla(page, "Proyecto con un diagrama (nombre largo + nombre de diagrama largo)");

const shareBtn = await page.evaluateHandle(() => [...document.querySelectorAll("button")].find((b) => b.textContent === "Compartir"));
await shareBtn.asElement()?.click();
await page.waitForSelector(".share-modal", { timeout: 5000 });
await new Promise((r) => setTimeout(r, 500));
await evaluarPantalla(page, "Modal de compartir (vacio: sin invitaciones todavia)");

await page.select(".share-modal select", "EDITOR");
await page.click(".share-modal__inline-form .modal__primary");
await page.waitForFunction(() => [...document.querySelectorAll(".share-modal__list-item button")].some((b) => b.textContent?.includes("Copiar")), { timeout: 8000 });
await new Promise((r) => setTimeout(r, 300));
await evaluarPantalla(page, "Modal de compartir (con una invitacion activa)");

// Pantalla de invitacion (publica, sin sesion) -- pido su propia url real por REST.
const loginRes = await fetch("http://localhost:8080/api/v1/auth/login", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: "owner@modelcollab.dev", password: "Modelcollab1!" }),
});
const { token } = await loginRes.json();
const projectId = page.url().match(/projects\/([^/]+)/)?.[1];
const invRes = await fetch(`http://localhost:8080/api/v1/projects/${projectId}/invitations`, { headers: { Authorization: `Bearer ${token}` } });
const invitaciones = await invRes.json();
const inviteUrl = invitaciones.find((i) => i.active)?.url;

if (inviteUrl) {
  const page2 = await browser.newPage();
  await page2.setViewport({ width: 1400, height: 900 });
  await page2.goto(inviteUrl, { waitUntil: "networkidle0" });
  await page2.waitForSelector(".invite-page__card", { timeout: 8000 });
  await new Promise((r) => setTimeout(r, 500));
  await evaluarPantalla(page2, "Pagina de invitacion (/invite/{token}, sin sesion)");
  await page2.close();
}

console.log(`\n${fallas === 0 ? "TODO OK" : `${fallas} falla(s)`}`);
await browser.close();
process.exitCode = fallas === 0 ? 0 : 1;
