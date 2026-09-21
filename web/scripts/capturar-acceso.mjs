// Captura las pantallas de acceso para tener el antes y el despues del pulido.
//
// Entra como el usuario sembrado, va a la lista de proyectos y saca la foto. Ademas fuerza el
// estado VACIO —que es el que mas cambio y el que ve alguien recien registrado— registrando un
// usuario nuevo al vuelo, porque el sembrado ya tiene proyectos.
//
// Uso:  node scripts/capturar-acceso.mjs <sufijo>      (ej: antes / despues)
//       con Vite en :5173 y el backend en :8080

import puppeteer from "puppeteer-core";
import { mkdirSync } from "node:fs";
import { join } from "node:path";

const SUFIJO = process.argv[2] ?? "captura";
const APP = "http://localhost:5173";
const API = "http://localhost:8080";
const DESTINO = join("..", "evidencias", "acceso-pulido");
mkdirSync(DESTINO, { recursive: true });

const CHROME = process.env.CHROME_PATH
  ?? "C:/Program Files/Google/Chrome/Application/chrome.exe";

const navegador = await puppeteer.launch({
  executablePath: CHROME,
  headless: "new",
  args: ["--no-sandbox", "--force-device-scale-factor=2"],
});

async function foto(email, password, nombre) {
  const page = await navegador.newPage();
  await page.setViewport({ width: 1100, height: 760 });
  await page.goto(APP, { waitUntil: "networkidle0" });

  // Se entra por el formulario, como entra una persona. La aplicacion no guarda la sesion en
  // localStorage, asi que inyectarla seria inventar un camino que no existe.
  await page.type('input[type="email"]', email);
  await page.type('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForSelector(".access-page__body", { timeout: 15000 });
  await new Promise((r) => setTimeout(r, 900));

  const archivo = join(DESTINO, `${nombre}-${SUFIJO}.png`);
  await page.screenshot({ path: archivo });
  console.log("  " + archivo);
  await page.close();
}

// 1. Con proyectos: el usuario sembrado ya tiene varios.
await foto("owner@modelcollab.dev", "Modelcollab1!", "lista-con-proyectos");

// 2. Vacio: uno recien registrado, que es exactamente quien ve ese estado.
const nuevo = `vacio-${Date.now()}@prueba.local`;
const CLAVE = "clave-de-prueba-12345";
const alta = await fetch(`${API}/api/v1/auth/register`, {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: nuevo, password: CLAVE, fullName: "Sin Proyectos" }),
});
if (!alta.ok) throw new Error("no se pudo registrar: " + alta.status);
await foto(nuevo, CLAVE, "lista-vacia");

// 3. Un proyecto SIN diagramas: es donde cae Daniel en el Acto 1, apenas lo crea.
{
  const correo = `proyvacio-${Date.now()}@prueba.local`;
  const CLAVE = "clave-de-prueba-12345";
  const alta = await (await fetch(`${API}/api/v1/auth/register`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: correo, password: CLAVE, fullName: "Proyecto Vacio" }),
  })).json();
  const h = { Authorization: `Bearer ${alta.token}`, "Content-Type": "application/json" };
  const proyecto = await (await fetch(`${API}/api/v1/projects`, {
    method: "POST", headers: h, body: JSON.stringify({ name: "Colegio" }),
  })).json();

  const page = await navegador.newPage();
  await page.setViewport({ width: 1100, height: 760 });
  await page.goto(APP, { waitUntil: "networkidle0" });
  await page.type('input[type="email"]', correo);
  await page.type('input[type="password"]', CLAVE);
  await page.click('button[type="submit"]');
  await page.waitForSelector(".access-page__body", { timeout: 15000 });
  // Se entra CLICKEANDO la fila, no con page.goto: la aplicacion guarda la sesion en memoria,
  // asi que recargar la URL directa la pierde y rebota al login. Me paso: la captura salio
  // siendo la pantalla de acceso.
  await page.click(".access-list__item");
  await page.waitForSelector(".access-page__empty-box", { timeout: 15000 });
  await new Promise((r) => setTimeout(r, 700));
  const archivo = join(DESTINO, `proyecto-sin-diagramas-${SUFIJO}.png`);
  await page.screenshot({ path: archivo });
  console.log("  " + archivo);
  await page.close();
}

await navegador.close();
console.log(`\nCapturas "${SUFIJO}" listas.\n`);
