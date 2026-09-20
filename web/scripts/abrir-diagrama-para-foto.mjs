import puppeteer from 'puppeteer-core';

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PROJECT_ID = '829fe6c2-ca56-43f6-b854-3e7c17e8fa9c';
const DIAGRAM_ID = 'e7bbedd8-030f-4ae3-b423-3182c92ea7e5';
const APP_URL = `http://localhost:5173/projects/${PROJECT_ID}/diagrams/${DIAGRAM_ID}`;

const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--window-size=1400,900'] });
const page = await browser.newPage();
await page.setViewport({ width: 1400, height: 900 });

await page.goto(APP_URL, { waitUntil: 'networkidle0' });
await page.waitForSelector('input[type="email"]', { timeout: 8000 });
await page.type('input[type="email"]', 'movil-test@modelcollab.dev');
await page.type('input[type="password"]', 'MovilTest1!');
await page.click('button[type="submit"]');
await page.waitForSelector('.react-flow', { timeout: 8000 });
await page.waitForFunction(() => document.querySelector('.app__collab-status')?.getAttribute('data-status') === 'conectado', { timeout: 8000 });

console.log('LISTO: web logueada y conectada al diagrama, esperando VISION_PROPOSAL...');

// Se queda escuchando la consola de la pagina por si el evento deja algun rastro,
// y no cierra el browser -- el proceso se mantiene vivo hasta que se lo mate.
page.on('console', (m) => console.log('  [pagina]', m.text()));

// Chequeo periodico de si aparecio el modal/aviso de importacion.
setInterval(async () => {
  const hayAviso = await page.evaluate(() => !!document.querySelector('.vision-import, [class*="vision"]'));
  if (hayAviso) console.log('POSIBLE AVISO DE VISION EN PANTALLA');
}, 3000);
