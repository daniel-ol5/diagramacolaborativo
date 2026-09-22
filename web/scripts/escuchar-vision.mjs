// Suscriptor STOMP liviano (sin Chrome) al diagrama de prueba, para el pase
// del telefono: el servidor solo necesita un SUBSCRIBE al topic de la sala
// para considerar que "alguien tiene el diagrama abierto" -- no hace falta
// un navegador real corriendo la UI (que en esta maquina, con memoria
// ajustada, se cae).
const BASE = process.env.BASE ?? 'http://localhost:8080';
const WS = BASE.replace('http', 'ws') + '/ws';
const NUL = String.fromCharCode(0);
const DIAGRAM_ID = 'e7bbedd8-030f-4ae3-b423-3182c92ea7e5';

function frame(comando, cabeceras, cuerpo = '') {
  const lineas = Object.entries(cabeceras).map(([k, v]) => `${k}:${v}`);
  return `${comando}\n${lineas.join('\n')}\n\n${cuerpo}${NUL}`;
}
function parsear(texto) {
  const corte = texto.indexOf('\n\n');
  const cabeza = texto.slice(0, corte).split('\n');
  const cabeceras = {};
  for (const linea of cabeza.slice(1)) {
    const i = linea.indexOf(':');
    if (i > 0) cabeceras[linea.slice(0, i)] = linea.slice(i + 1);
  }
  return { comando: cabeza[0], cabeceras, cuerpo: texto.slice(corte + 2).replace(/\0$/, '') };
}

const login = await fetch(`${BASE}/api/v1/auth/login`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'movil-test@modelcollab.dev', password: 'MovilTest1!' }),
}).then((r) => r.json());
const token = login.token;
console.log('logueado como', login.email, login.userId);

const ws = new WebSocket(WS);
ws.onopen = () => ws.send(frame('CONNECT', { 'accept-version': '1.2', host: 'localhost', Authorization: `Bearer ${token}` }));
ws.onmessage = (ev) => {
  const f = parsear(String(ev.data));
  if (f.comando === 'CONNECTED') {
    ws.send(frame('SUBSCRIBE', { id: 'sala', destination: `/topic/diagrams/${DIAGRAM_ID}` }));
    ws.send(frame('SUBSCRIBE', { id: 'vision', destination: '/user/queue/vision' }));
    console.log('LISTO: suscrito al diagrama y a /user/queue/vision, esperando la foto del telefono...');
    return;
  }
  if (f.comando === 'MESSAGE') {
    console.log('--- mensaje recibido ---');
    console.log('destino:', f.cabeceras.destination);
    console.log('cuerpo:', f.cuerpo.slice(0, 2000));
    if (f.cabeceras.destination?.includes('/queue/vision')) {
      console.log('>>> ES LA PROPUESTA DE VISION <<<');
    }
  }
  if (f.comando === 'ERROR') console.error('ERROR STOMP:', f.cuerpo);
};
ws.onerror = (e) => console.error('error de socket:', e.message);
ws.onclose = () => console.log('socket cerrado');

// Se queda vivo escuchando; no hay browser, footprint minimo.
setInterval(() => {}, 60000);
