import puppeteer from 'puppeteer-core';

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const HTTP_BASE = 'http://localhost:8080';
async function json(ruta, opciones = {}) {
  const r = await fetch(HTTP_BASE + ruta, opciones);
  return { status: r.status, cuerpo: await r.json().catch(() => null) };
}
const sello = Date.now();
const alta = await json('/api/v1/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'owner@modelcollab.dev', password: 'Modelcollab1!' }),
});
const token = alta.cuerpo.token;
const proyecto = await json('/api/v1/projects', {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ name: 'Pulido ' + sello }),
});
const diagrama = await json(`/api/v1/projects/${proyecto.cuerpo.id}/diagrams`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  body: JSON.stringify({ name: 'C' }),
});
const APP_URL = `http://localhost:5173/projects/${proyecto.cuerpo.id}/diagrams/${diagrama.cuerpo.id}`;

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--window-size=900,700'] });
const page = await browser.newPage();
await page.setViewport({ width: 900, height: 700 });
await page.goto(APP_URL, { waitUntil: 'networkidle0' });
await page.waitForSelector('input[type="email"]', { timeout: 8000 });
await page.type('input[type="email"]', 'owner@modelcollab.dev');
await page.type('input[type="password"]', 'Modelcollab1!');
await page.click('button[type="submit"]');
await page.waitForSelector('.react-flow', { timeout: 8000 });

await page.click('.app__new-class-btn');
await page.waitForSelector('#new-class-name', { timeout: 5000 });
await page.type('#new-class-name', 'Cliente');
await page.click('.modal__primary');
await page.waitForFunction(() => document.querySelectorAll('.uml-class-node').length === 1, { timeout: 8000 });

// sin el mouse encima: los controles de agregar/editar deberian estar invisibles
await page.mouse.move(50, 50);
await new Promise((r) => setTimeout(r, 200));
await page.screenshot({ path: 'C:/Users/Daniel/Desktop/SW1/Productividad/evidencias/pulido-lienzo/sin-hover.png' });

const opacidadSinHover = await page.evaluate(() => {
  const btn = document.querySelector('.uml-class-node__add-attr');
  return getComputedStyle(btn).opacity;
});

// con el mouse encima de la caja
const box = await page.evaluate(() => {
  const r = document.querySelector('.uml-class-node').getBoundingClientRect();
  return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
});
await page.mouse.move(box.x, box.y);
await new Promise((r) => setTimeout(r, 200));
await page.screenshot({ path: 'C:/Users/Daniel/Desktop/SW1/Productividad/evidencias/pulido-lienzo/con-hover.png' });

const opacidadConHover = await page.evaluate(() => {
  const btn = document.querySelector('.uml-class-node__add-attr');
  return getComputedStyle(btn).opacity;
});

console.log('opacidad sin hover:', opacidadSinHover, '(esperado: 0)');
console.log('opacidad con hover:', opacidadConHover, '(esperado: 1)');
console.log(opacidadSinHover === '0' && opacidadConHover === '1' ? 'PASA' : 'FALLA');

await browser.close();
