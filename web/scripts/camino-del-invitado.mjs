// El camino de quien recibe un enlace y TODAVIA NO TIENE CUENTA, caminado entero en un navegador
// de verdad.
//
// POR QUE EXISTE: es el unico camino del guion que nadie recorrio nunca como lo recorre una
// persona. Los arneses, las capturas y las pruebas SIEMPRE crearon los usuarios por la API, que
// es exactamente el motivo por el que la pantalla de registro no existio durante cuatro meses sin
// que se notara (061ad6e). El invitado toca cuatro piezas que tienen que ponerse de acuerdo y que
// nadie confronto nunca juntas:
//
//   1. el backend arma la URL      -> app.public-base-url + /invite/{token}
//   2. el ruteo web sirve esa ruta -> AppRoot: <Route path="/invite/:token">
//   3. InvitePage manda al login    -> navigate("/login", { state: { from: location } })
//   4. LoginRoute tiene que VOLVER  -> navigate(from.pathname)
//
// Si el paso 4 se pierde, el invitado se registra y aterriza en una lista de proyectos vacia, sin
// ninguna pista de que existia una invitacion. El enlace ya se consumio de su portapapeles. Ese
// es el peor momento posible para que se rompa una demostracion, y es invisible desde la API.
//
// Uso:  node scripts/camino-del-invitado.mjs
//       MC_ROMPER=1 node scripts/camino-del-invitado.mjs   -> con un token inventado; TIENE que
//                                                             dar rojo. Es la prueba de que el
//                                                             arnes mide algo.
//
// Necesita Vite en :5173 y el backend en :8080. No toca datos de nadie: crea su propia cuenta
// duenia y su propio invitado, los dos con correos @prueba.local.

import puppeteer from "puppeteer-core";

const APP = process.env.MC_APP ?? "http://localhost:5173";
const API = process.env.MC_API ?? "http://localhost:8080";
const ROMPER = process.env.MC_ROMPER === "1";
const CHROME = process.env.CHROME_PATH ?? "C:/Program Files/Google/Chrome/Application/chrome.exe";

let fallos = 0;
function comprobar(ok, texto, detalle = "") {
  console.log(`${ok ? "  OK  " : " FALLA"}  ${texto}${detalle ? "\n          " + detalle : ""}`);
  if (!ok) fallos++;
  return ok;
}

async function api(ruta, opciones = {}) {
  const r = await fetch(API + ruta, opciones);
  const texto = await r.text();
  if (r.status >= 300) throw new Error(`${ruta} -> ${r.status} ${texto.slice(0, 200)}`);
  return texto ? JSON.parse(texto) : null;
}

const sello = Date.now().toString().slice(-6);
const CLAVE = "clave-de-prueba-12345";

// ------------------------------------------------ el duenio prepara la invitacion (por API)

console.log("\nPreparando: una duenia, un proyecto, un enlace de invitacion\n");

const duenia = await api("/api/v1/auth/register", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: `duenia-${sello}@prueba.local`, password: CLAVE, fullName: "Duenia de Prueba" }),
});
const auth = { Authorization: `Bearer ${duenia.token}`, "Content-Type": "application/json" };

const NOMBRE_PROYECTO = `Colegio ${sello}`;
const proyecto = await api("/api/v1/projects", {
  method: "POST", headers: auth,
  body: JSON.stringify({ name: NOMBRE_PROYECTO, description: "Gestion academica" }),
});
const invitacion = await api(`/api/v1/projects/${proyecto.id}/invitations`, {
  method: "POST", headers: auth, body: JSON.stringify({ role: "EDITOR", expiresInDays: 7 }),
});

// ------------------------------------------------ 1. la costura backend <-> ruta web

console.log("1. La URL que el backend le pone al enlace");
console.log(`   ${invitacion.url}`);
comprobar(invitacion.url.startsWith(APP + "/invite/"),
  "apunta al lienzo web, no a la API",
  `esperaba que empezara con ${APP}/invite/ — si apunta a :8080 el enlace copiado no abre nada`);

const enlace = ROMPER
  ? `${APP}/invite/token-que-no-existe-${sello}`
  : invitacion.url;
if (ROMPER) console.log(`\n   MC_ROMPER=1 — se usa un token inventado: ${enlace}\n`);

// ------------------------------------------------ el navegador, sin sesion

const navegador = await puppeteer.launch({
  executablePath: CHROME,
  headless: "new",
  args: ["--no-sandbox"],
});
// Contexto aparte: un invitado llega con el navegador limpio, sin nada de la duenia.
const contexto = await navegador.createBrowserContext();
const page = await contexto.newPage();
await page.setViewport({ width: 1100, height: 760 });

const texto = () => page.$eval("body", (b) => b.innerText);
const clickPorTexto = async (selector, contiene) => {
  const encontrado = await page.evaluate((sel, txt) => {
    const b = [...document.querySelectorAll(sel)].find((e) => e.innerText.includes(txt));
    if (b) b.click();
    return !!b;
  }, selector, contiene);
  if (!encontrado) throw new Error(`no encontre un ${selector} que diga "${contiene}"`);
};

try {
  // ---------------------------------------------- 2. la invitacion se ve sin estar logueado

  console.log("\n2. El invitado abre el enlace sin tener sesion");
  await page.goto(enlace, { waitUntil: "networkidle0" });
  await page.waitForFunction(() => !document.body.innerText.includes("Consultando"), { timeout: 15000 });
  const enLaTarjeta = await texto();

  comprobar(enLaTarjeta.includes(NOMBRE_PROYECTO),
    "la tarjeta dice a QUE proyecto lo invitaron", enLaTarjeta.replace(/\n+/g, " | "));
  comprobar(enLaTarjeta.includes("Editor"),
    "y con que rol, en castellano");
  comprobar(!/\bEDITOR\b/.test(enLaTarjeta),
    "sin mostrar la constante cruda del backend");

  // ---------------------------------------------- 3. de la invitacion al registro

  console.log("\n3. No tiene cuenta: tiene que poder crearla desde aca");
  const ofreceCrearCuenta = /crear|regist/i.test(enLaTarjeta);
  comprobar(ofreceCrearCuenta,
    "la tarjeta menciona crear una cuenta, no solo iniciar sesion",
    "quien recibe una invitacion por correo normalmente NO tiene cuenta todavia");

  // Por clase y no por texto: el arnes no tiene que romperse porque se reescriba un boton
  // (ya me paso al cambiar "Iniciar sesion para aceptar" por "Entrar o crear cuenta").
  await page.click(".invite-page__action");
  await page.waitForFunction(() => location.pathname === "/login", { timeout: 10000 });
  comprobar(true, "el boton lleva a /login");

  await clickPorTexto("button", "No tengo cuenta");
  await page.waitForFunction(() => document.body.innerText.includes("Creá tu cuenta"), { timeout: 5000 });
  comprobar(true, "y desde el login se llega al alta de cuenta");

  // ---------------------------------------------- 4. se registra

  console.log("\n4. Se registra");
  const CORREO_INVITADO = `invitado-${sello}@prueba.local`;
  await page.type('input[type="text"]', "Invitado de Prueba");
  await page.type('input[type="email"]', CORREO_INVITADO);
  await page.type('input[type="password"]', CLAVE);
  await page.click('button[type="submit"]');

  // ---------------------------------------------- 5. LA VUELTA: el paso que se pierde solo

  console.log("\n5. La vuelta al enlace (el paso que nadie confronto nunca)");
  const volvio = await page
    .waitForFunction(() => location.pathname.startsWith("/invite/"), { timeout: 15000 })
    .then(() => true)
    .catch(() => false);
  comprobar(volvio,
    "despues de crear la cuenta vuelve SOLO a la invitacion",
    volvio ? "" : `quedo en ${page.url()} — la invitacion se perdio y el invitado no tiene como volver`);

  if (volvio) {
    await page.waitForFunction(() => !document.body.innerText.includes("Consultando"), { timeout: 15000 });
    await page.click(".invite-page__action");

    // ------------------------------------------- 6. entra al proyecto como Editor

    console.log("\n6. Acepta y entra al proyecto");
    const entro = await page
      .waitForFunction((id) => location.pathname === `/projects/${id}`, { timeout: 15000 }, proyecto.id)
      .then(() => true)
      .catch(() => false);
    comprobar(entro, "aterriza DENTRO del proyecto al que lo invitaron", entro ? "" : `quedo en ${page.url()}`);

    if (entro) {
      await new Promise((r) => setTimeout(r, 800));
      const enElProyecto = await texto();
      comprobar(enElProyecto.includes(NOMBRE_PROYECTO), "y la pantalla es la del proyecto correcto");
      comprobar(!/\b(EDITOR|OWNER|VIEWER)\b/.test(enElProyecto), "tampoco aca se escapa el rol crudo");
    }

    // ------------------------------------------- 7. el servidor tambien lo cree

    const miembros = await api(`/api/v1/projects/${proyecto.id}/members`, { headers: auth });
    const invitado = miembros.find((m) => m.email === CORREO_INVITADO);
    comprobar(!!invitado && invitado.role === "EDITOR",
      "y el servidor lo tiene como miembro EDITOR (no solo la pantalla)",
      invitado ? `role=${invitado.role}` : `miembros: ${miembros.map((m) => m.email).join(", ")}`);
  }
} catch (e) {
  comprobar(false, "el recorrido se corto", String(e).slice(0, 300) + `\n          URL: ${page.url()}`);
  await page.screenshot({ path: "../evidencias/camino-del-invitado-roto.png" }).catch(() => {});
} finally {
  await contexto.close().catch(() => {});
  await navegador.close().catch(() => {});
}

console.log(fallos === 0
  ? "\nEL INVITADO LLEGA: enlace -> cuenta nueva -> adentro del proyecto.\n"
  : `\n${fallos} comprobacion(es) en rojo.\n`);
process.exit(fallos === 0 ? 0 : 1);
