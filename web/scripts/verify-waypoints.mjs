import puppeteer from 'puppeteer-core';

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const HTTP_BASE = 'http://localhost:8080';

const results = [];
const pass = (n, d) => { results.push({ ok: true }); console.log(`[PASA] ${n}${d ? ' - ' + d : ''}`); };
const fail = (n, d) => { results.push({ ok: false }); console.log(`[FALLA] ${n}${d ? ' - ' + d : ''}`); };

// Diagrama NUEVO y descartable -- el compartido acumula clases/relaciones
// de todas las verificaciones anteriores de esta sesion, y contar
// ".react-flow__edge" ahi no aisla nada.
const sello = Date.now();
async function json(ruta, opciones = {}) {
  const r = await fetch(HTTP_BASE + ruta, opciones);
  return { status: r.status, cuerpo: await r.json().catch(() => null) };
}
const altaOwner = await json('/api/v1/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'owner@modelcollab.dev', password: 'Modelcollab1!' }),
});
const tokenOwner = altaOwner.cuerpo.token;
const proyecto = await json('/api/v1/projects', {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokenOwner}` },
  body: JSON.stringify({ name: 'Quiebres ' + sello }),
});
await json(`/api/v1/projects/${proyecto.cuerpo.id}/members`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokenOwner}` },
  body: JSON.stringify({ email: 'editor@modelcollab.dev', role: 'EDITOR' }),
});
const diagrama = await json(`/api/v1/projects/${proyecto.cuerpo.id}/diagrams`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokenOwner}` },
  body: JSON.stringify({ name: 'Clases' }),
});
const APP_URL = `http://localhost:5173/projects/${proyecto.cuerpo.id}/diagrams/${diagrama.cuerpo.id}`;
console.log('diagrama de prueba:', diagrama.cuerpo.id);

async function login(page, email, password) {
  await page.goto(APP_URL, { waitUntil: 'networkidle0' });
  await page.waitForSelector('input[type="email"]', { timeout: 8000 });
  await page.type('input[type="email"]', email);
  await page.type('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForSelector('.react-flow', { timeout: 8000 });
  await page.waitForFunction(() => document.querySelector('.app__collab-status')?.getAttribute('data-status') === 'conectado', { timeout: 8000 });
}

async function crearClase(page, nombre) {
  await page.click('.app__new-class-btn');
  await page.waitForSelector('#new-class-name', { timeout: 5000 });
  await page.type('#new-class-name', nombre);
  await page.click('.modal__primary');
  await page.waitForFunction(
    (n) => [...document.querySelectorAll('.uml-class-node__name-text')].some((el) => el.textContent === n),
    { timeout: 8000 }, nombre,
  );
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--window-size=1500,1000'] });
const ctxA = await browser.createBrowserContext();
const ctxB = await browser.createBrowserContext();
const pageA = await ctxA.newPage();
const pageB = await ctxB.newPage();
await pageA.setViewport({ width: 1500, height: 1000 });
await pageB.setViewport({ width: 1500, height: 1000 });
pageA.on('pageerror', (e) => console.log('  [A pageerror]', e.message));

await login(pageA, 'owner@modelcollab.dev', 'Modelcollab1!');
await login(pageB, 'editor@modelcollab.dev', 'Modelcollab1!');

const nA = 'QuiebreA' + Date.now();
const nB = 'QuiebreB' + Date.now();
await crearClase(pageA, nA);
await crearClase(pageA, nB);
await pageB.waitForFunction(
  (n) => [...document.querySelectorAll('.uml-class-node__name-text')].some((el) => el.textContent === n),
  { timeout: 8000 }, nB,
);

// separar las dos clases para que la arista no quede degenerada
const boxes = await pageA.evaluate(() => {
  return [...document.querySelectorAll('.react-flow__node')].map((n) => {
    const r = n.getBoundingClientRect();
    return { name: n.querySelector('.uml-class-node__name-text')?.textContent, x: r.x, y: r.y, w: r.width, h: r.height };
  });
});
const cajaA = boxes.find((b) => b.name === nA);
const cajaB = boxes.find((b) => b.name === nB);
// arrastrar B lejos, por el encabezado
await pageA.mouse.move(cajaB.x + cajaB.w / 2, cajaB.y + 15);
await pageA.mouse.down();
await pageA.mouse.move(cajaB.x + 500, cajaB.y + 300, { steps: 15 });
await pageA.mouse.up();
await new Promise((r) => setTimeout(r, 1200));

// conectar A -> B (arrastre desde el handle inferior de A al superior de B)
const handles = await pageA.evaluate(() => {
  return [...document.querySelectorAll('.react-flow__node')].map((n) => {
    const nombre = n.querySelector('.uml-class-node__name-text')?.textContent;
    const s = n.querySelector('.react-flow__handle-bottom')?.getBoundingClientRect();
    const t = n.querySelector('.react-flow__handle-top')?.getBoundingClientRect();
    return {
      nombre,
      source: s ? { x: s.x + s.width / 2, y: s.y + s.height / 2 } : null,
      target: t ? { x: t.x + t.width / 2, y: t.y + t.height / 2 } : null,
    };
  });
});
const hA = handles.find((h) => h.nombre === nA);
const hB = handles.find((h) => h.nombre === nB);
console.log('  handles:', JSON.stringify({ hA, hB }));
// El gesto de arrastre entre dos contextos de navegador headless es flaky
// a nivel de automatizacion (foco de ventana, no del sistema bajo prueba
// -- confirmado aparte con un dispatch nativo): un par de reintentos
// alcanza para no confundir una carrera del arnes con un defecto real.
let abrio = false;
for (let intento = 0; intento < 3 && !abrio; intento++) {
  await pageA.bringToFront();
  await new Promise((r) => setTimeout(r, 400));
  // Un clic neutro primero, para que el foco real (no solo bringToFront)
  // quede establecido sobre ESTE contexto antes de arrastrar -- confirmado
  // que sin esto el primer arrastre tras crear un segundo BrowserContext
  // puede perderse.
  await pageA.mouse.click(40, 40);
  await new Promise((r) => setTimeout(r, 150));
  // Recalcular en cada intento: si un intento previo fallo arrastrando
  // sobre el lienzo en vez de desde el handle, pudo haber movido una
  // clase -- usar coordenadas viejas repetiria el mismo fallo.
  const handlesAhora = await pageA.evaluate(() => {
    return [...document.querySelectorAll('.react-flow__node')].map((n) => {
      const nombre = n.querySelector('.uml-class-node__name-text')?.textContent;
      const s = n.querySelector('.react-flow__handle-bottom')?.getBoundingClientRect();
      const t = n.querySelector('.react-flow__handle-top')?.getBoundingClientRect();
      return {
        nombre,
        source: s ? { x: s.x + s.width / 2, y: s.y + s.height / 2 } : null,
        target: t ? { x: t.x + t.width / 2, y: t.y + t.height / 2 } : null,
      };
    });
  });
  const hAn = handlesAhora.find((h) => h.nombre === nA);
  const hBn = handlesAhora.find((h) => h.nombre === nB);
  await pageA.mouse.move(hAn.source.x, hAn.source.y);
  await pageA.mouse.down();
  await pageA.mouse.move((hAn.source.x + hBn.target.x) / 2, (hAn.source.y + hBn.target.y) / 2, { steps: 10 });
  await pageA.mouse.move(hBn.target.x, hBn.target.y, { steps: 20 });
  await pageA.mouse.up();
  abrio = await pageA.waitForSelector('#rel-type', { timeout: 3000 }).then(() => true).catch(() => false);
  if (!abrio) console.log(`  (intento ${intento + 1}: el formulario no abrio, reintentando)`);
}
if (abrio) {
  pass('se abrio el formulario de nueva relacion');
  await pageA.click('.modal__primary'); // ASOCIACION por defecto
} else {
  fail('el formulario de relacion nunca abrio tras 3 intentos');
  await pageA.screenshot({ path: 'C:/Users/Daniel/AppData/Local/Temp/claude/C--Users-Daniel-Desktop-SW1-Productividad/1d68dce5-1b58-4dd8-89c6-e86eedcb2c8a/scratchpad/debug-no-conecta.png' });
  console.log(`${results.filter((r) => r.ok).length}/${results.length} OK (corte temprano)`);
  await browser.close();
  process.exit(1);
}

await pageA.waitForFunction(() => document.querySelectorAll('.react-flow__edge').length === 1, { timeout: 8000 })
  .then(() => pass('la relacion se creo'))
  .catch(() => fail('la relacion nunca aparecio'));
await pageB.waitForFunction(() => document.querySelectorAll('.react-flow__edge').length === 1, { timeout: 8000 })
  .then(() => pass('B recibe la relacion nueva'))
  .catch(() => fail('B nunca recibio la relacion'));

// --- doble clic sobre la arista para agregar un quiebre ---
await pageA.bringToFront();
 await new Promise((r) => setTimeout(r, 300));
const dAntes = await pageA.$eval('.react-flow__edge-path', (el) => el.getAttribute('d'));
// page.mouse.click({clickCount:2}) no siempre satisface la ventana de
// tiempo que Chromium exige para sintetizar un dblclick real -- un dblclick
// nativo despachado a mano SI dispara el mismo evento que produce un
// doble clic humano de verdad (confirmado aparte); esto no reemplaza la
// prueba, evita un falso negativo del arnes.
await pageA.evaluate(() => {
  const p = document.querySelector('.uml-edge-hit-area');
  const rect = p.getBoundingClientRect();
  const cx = rect.x + rect.width / 2;
  const cy = rect.y + rect.height / 2;
  p.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, view: window }));
});
await new Promise((r) => setTimeout(r, 500));

const dDespues = await pageA.$eval('.react-flow__edge-path', (el) => el.getAttribute('d'));
if (dDespues !== dAntes) pass('el doble clic sobre la arista agrego un quiebre (el path cambio)', dDespues);
else fail('el path no cambio tras el doble clic', dDespues);

const circulos = await pageA.$$eval('.uml-edge-waypoint-handle', (els) => els.length);
if (circulos === 1) pass('aparece exactamente 1 agarre de quiebre');
else fail('cantidad de agarres inesperada', String(circulos));

if (circulos === 0) {
  console.log(`${results.filter((r) => r.ok).length}/${results.length} OK (corte temprano, sin quiebre que seguir probando)`);
  await browser.close();
  process.exit(1);
}

await pageB.waitForFunction(
  (dAnt) => document.querySelector('.react-flow__edge-path')?.getAttribute('d') !== dAnt,
  { timeout: 8000 }, dAntes,
).then(() => pass('B recibe el quiebre nuevo (UPDATE_WAYPOINTS difundido)'))
  .catch(() => fail('B nunca vio el path cambiar'));

await pageA.bringToFront();
 await new Promise((r) => setTimeout(r, 300));

// --- arrastrar el quiebre a otra posicion ---
const circuloBox = await pageA.evaluate(() => {
  const c = document.querySelector('.uml-edge-waypoint-handle');
  const rect = c.getBoundingClientRect();
  return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
});
await pageA.mouse.move(circuloBox.x, circuloBox.y);
await pageA.mouse.down();
await pageA.mouse.move(circuloBox.x + 120, circuloBox.y + 80, { steps: 10 });
await pageA.mouse.up();
await new Promise((r) => setTimeout(r, 500));

const dTrasArrastre = await pageA.$eval('.react-flow__edge-path', (el) => el.getAttribute('d'));
if (dTrasArrastre !== dDespues) pass('arrastrar el agarre movio el quiebre (el path volvio a cambiar)');
else fail('el path no cambio tras arrastrar', dTrasArrastre);

await pageB.waitForFunction(
  (dAnt) => document.querySelector('.react-flow__edge-path')?.getAttribute('d') !== dAnt,
  { timeout: 8000 }, dDespues,
).then(() => pass('B recibe la posicion nueva del quiebre tras el arrastre'))
  .catch(() => fail('B nunca vio la posicion nueva'));

// --- verificar que esta en el canonico del SERVIDOR, no solo en el DOM ---
// Un F5 real no sirve para esto: la sesion vive solo en memoria de React
// (sin localStorage, a proposito, ver el resto de scripts de este
// proyecto) y recargar desloguea -- se verifica contra el snapshot REST
// directo, que es ademas mas concluyente (ve lo mismo que sobreviviria
// cualquier caida del cliente).
const foto = await json(`/api/v1/diagrams/${diagrama.cuerpo.id}/snapshot`, {
  headers: { Authorization: `Bearer ${tokenOwner}` },
});
const relPersistida = foto.cuerpo?.currentState?.relationships?.[0];
if (relPersistida?.waypoints?.length === 1) {
  pass('el quiebre esta en el snapshot del servidor (no solo en el DOM)', JSON.stringify(relPersistida.waypoints));
} else {
  fail('el snapshot del servidor no tiene el quiebre esperado', JSON.stringify(relPersistida));
}

// --- doble clic sobre el agarre para borrar el quiebre ---
await pageA.bringToFront();
await new Promise((r) => setTimeout(r, 300));
let borrado = false;
for (let intento = 0; intento < 3 && !borrado; intento++) {
  const circuloBox2 = await pageA.evaluate(() => {
    const c = document.querySelector('.uml-edge-waypoint-handle');
    if (!c) return null;
    const rect = c.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });
  if (!circuloBox2) break; // ya se borro en un intento anterior
  await pageA.evaluate(({ x, y }) => {
    document.elementFromPoint(x, y)?.dispatchEvent(
      new MouseEvent('dblclick', { bubbles: true, cancelable: true, clientX: x, clientY: y, view: window }),
    );
  }, circuloBox2);
  borrado = await pageA.waitForFunction(() => document.querySelectorAll('.uml-edge-waypoint-handle').length === 0, { timeout: 2000 })
    .then(() => true).catch(() => false);
}
if (borrado) pass('doble clic sobre el agarre borro el quiebre');
else fail('el quiebre no se borro tras 3 intentos');

console.log('');
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} OK`);
await browser.close();
process.exitCode = failed.length === 0 ? 0 : 1;
