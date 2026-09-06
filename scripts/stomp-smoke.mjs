#!/usr/bin/env node
// ModelCollab :: humo de STOMP para el criterio de aceptacion del Dia 2
// ("un VIEWER recibe 403 al intentar mutar por STOMP").
//
// Sin argumentos, hace todo solo contra un backend + Postgres locales ya
// sembrados con scripts/seed-dev.sql:
//   node scripts/stomp-smoke.mjs
//
// Exit code 0 si todos los pasos pasan, distinto de 0 si alguno falla.
// MC_DEBUG=1 node scripts/stomp-smoke.mjs   -> loguea los frames STOMP crudos.
//
// Reusar para el Dia 3 (LOCK_DENIED entre dos clientes): las piezas login(),
// connectStomp() y createInbox() de aca abajo no son especificas de roles,
// sirven igual para el escenario de locking.

import { Client } from '@stomp/stompjs';
import WebSocket from 'ws';

const HTTP_BASE = process.env.MC_HTTP_BASE ?? 'http://localhost:8080';
const WS_URL = process.env.MC_WS_URL ?? 'ws://localhost:8080/ws';
const DIAGRAM_ID = process.env.MC_DIAGRAM_ID ?? '55555555-5555-5555-5555-555555555555';
// Clase "Cliente" del diagrama barberia.json sembrado por seed-dev.sql.
const TARGET_CLASS_ID = process.env.MC_TARGET_CLASS_ID ?? 'f4281e21-43e9-5959-97ad-7bcaaef35382';

const EDITOR = { email: 'editor@modelcollab.dev', password: 'Modelcollab1!' };
const VIEWER = { email: 'viewer@modelcollab.dev', password: 'Modelcollab1!' };

const CONNECT_TIMEOUT_MS = 5000;
const MESSAGE_TIMEOUT_MS = 5000;
const ABSENCE_WINDOW_MS = 2500;
const DEBUG = process.env.MC_DEBUG === '1';

const results = [];
function record(name, ok, detail) {
  results.push({ name, ok, detail: detail ?? '' });
  console.log(`[${ok ? 'PASA ' : 'FALLA'}] ${name}${detail ? ' - ' + detail : ''}`);
}
const pass = (name, detail) => record(name, true, detail);
const fail = (name, detail) => record(name, false, detail);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function parseBody(frame) {
  try {
    return JSON.parse(frame.body);
  } catch {
    return null;
  }
}

async function login(creds) {
  const res = await fetch(`${HTTP_BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: creds.email, password: creds.password }),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} al loguear ${creds.email}: ${text.slice(0, 300)}`);
  }
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`respuesta no-JSON al loguear ${creds.email}: ${text.slice(0, 300)}`);
  }
  if (!json.token) {
    throw new Error(`falta "token" en la respuesta de login de ${creds.email}: ${text.slice(0, 300)}`);
  }
  return json.token;
}

// Cola de mensajes de una suscripcion, con espera por predicado y timeout.
// Guarda el historial completo: un waitFor() posterior a la llegada del
// mensaje lo sigue encontrando (no hace falta ganarle una carrera al broker).
function createInbox(label) {
  const messages = [];
  const waiters = [];

  function push(frame) {
    if (DEBUG) console.log(`  [frame:${label}] ${frame.headers.destination} :: ${frame.body}`);
    messages.push(frame);
    for (let i = waiters.length - 1; i >= 0; i--) {
      const w = waiters[i];
      if (w.predicate(frame)) {
        waiters.splice(i, 1);
        clearTimeout(w.timer);
        w.resolve(frame);
      }
    }
  }

  function waitFor(predicate, timeoutMs, description) {
    const already = messages.find(predicate);
    if (already) return Promise.resolve(already);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const idx = waiters.findIndex((w) => w.resolve === resolve);
        if (idx >= 0) waiters.splice(idx, 1);
        reject(new Error(`timeout de ${timeoutMs}ms esperando en ${label}: ${description}`));
      }, timeoutMs);
      waiters.push({ predicate, resolve, timer });
    });
  }

  async function confirmAbsence(predicate, windowMs) {
    try {
      const frame = await waitFor(predicate, windowMs, '(se esperaba que NO llegara)');
      return { absent: false, frame };
    } catch {
      return { absent: true };
    }
  }

  return { push, waitFor, confirmAbsence };
}

function connectStomp(token, label) {
  return new Promise((resolve, reject) => {
    const client = new Client({
      webSocketFactory: () => new WebSocket(WS_URL),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 0,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
      debug: DEBUG ? (msg) => console.log(`  [stomp:${label}] ${msg}`) : () => {},
    });
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error(`timeout de ${CONNECT_TIMEOUT_MS}ms conectando STOMP (${label})`));
    }, CONNECT_TIMEOUT_MS);
    client.onConnect = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(client);
    };
    client.onStompError = (frame) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(new Error(`frame ERROR de STOMP conectando (${label}): ${frame.headers?.message ?? ''} ${frame.body ?? ''}`.trim()));
    };
    client.onWebSocketError = (evt) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(new Error(`error de WebSocket conectando (${label}): ${evt?.message ?? evt}`));
    };
    client.activate();
  });
}

function moveClassBody(clientMutationId, x, y) {
  return JSON.stringify({
    op: 'MOVE_CLASS',
    targetId: TARGET_CLASS_ID,
    clientMutationId,
    payload: { x, y },
  });
}

function finish() {
  console.log('');
  console.log('== resumen ==');
  const width = Math.max(20, ...results.map((r) => r.name.length));
  for (const r of results) {
    console.log(`${r.ok ? 'PASA ' : 'FALLA'} ${r.name.padEnd(width)}  ${r.detail}`);
  }
  const failed = results.filter((r) => !r.ok);
  console.log('');
  console.log(`${results.length - failed.length}/${results.length} pasos OK`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

async function main() {
  console.log('== ModelCollab :: humo STOMP (Dia 2) ==');
  console.log(`HTTP=${HTTP_BASE}  WS=${WS_URL}  diagrama=${DIAGRAM_ID}`);
  console.log('');

  let editorToken;
  let viewerToken;
  try {
    editorToken = await login(EDITOR);
    pass('login REST del EDITOR', 'token recibido');
  } catch (e) {
    fail('login REST del EDITOR', e.message);
  }
  try {
    viewerToken = await login(VIEWER);
    pass('login REST del VIEWER', 'token recibido');
  } catch (e) {
    fail('login REST del VIEWER', e.message);
  }
  if (!editorToken || !viewerToken) {
    fail('conexiones STOMP', 'no se intentan: falta al menos un login');
    return finish();
  }

  let editorClient;
  let viewerClient;
  const viewerCloseState = { closedUnexpectedly: false };
  try {
    editorClient = await connectStomp(editorToken, 'editor');
    pass('CONNECT STOMP del EDITOR', 'aceptado');
  } catch (e) {
    fail('CONNECT STOMP del EDITOR', e.message);
  }
  try {
    viewerClient = await connectStomp(viewerToken, 'viewer');
    viewerClient.onWebSocketClose = () => {
      viewerCloseState.closedUnexpectedly = true;
    };
    pass('CONNECT STOMP del VIEWER', 'aceptado');
  } catch (e) {
    fail('CONNECT STOMP del VIEWER', e.message);
  }
  if (!editorClient || !viewerClient) {
    return finish();
  }

  const editorTopic = createInbox('editor/topic');
  const editorErrors = createInbox('editor/errors');
  const viewerTopic = createInbox('viewer/topic');
  const viewerErrors = createInbox('viewer/errors');

  editorClient.subscribe(`/topic/diagrams/${DIAGRAM_ID}`, (m) => editorTopic.push(m));
  editorClient.subscribe('/user/queue/errors', (m) => editorErrors.push(m));
  viewerClient.subscribe(`/topic/diagrams/${DIAGRAM_ID}`, (m) => viewerTopic.push(m));
  viewerClient.subscribe('/user/queue/errors', (m) => viewerErrors.push(m));
  pass('suscripciones', 'EDITOR y VIEWER a /topic/diagrams/{id} y /user/queue/errors');

  // Deja que el broker procese los SUBSCRIBE antes de publicar.
  await sleep(300);

  // Paso 4: el EDITOR muta -> debe verse en el topic.
  editorClient.publish({
    destination: `/app/diagram/${DIAGRAM_ID}/mutate`,
    body: moveClassBody('mut-editor-1', 420, 180),
  });
  try {
    await Promise.any([
      viewerTopic.waitFor((f) => parseBody(f)?.clientMutationId === 'mut-editor-1', MESSAGE_TIMEOUT_MS, 'broadcast mut-editor-1'),
      editorTopic.waitFor((f) => parseBody(f)?.clientMutationId === 'mut-editor-1', MESSAGE_TIMEOUT_MS, 'broadcast mut-editor-1'),
    ]);
    pass('EDITOR muta -> se difunde en el topic', 'mut-editor-1 visto en /topic/diagrams/{id}');
  } catch (e) {
    const detail = e instanceof AggregateError ? e.errors.map((x) => x.message).join(' | ') : e.message;
    fail('EDITOR muta -> se difunde en el topic', detail);
  }

  // Paso 5: el VIEWER intenta mutar -> NO debe difundirse, SI debe recibir 403.
  viewerClient.publish({
    destination: `/app/diagram/${DIAGRAM_ID}/mutate`,
    body: moveClassBody('mut-viewer-1', 999, 999),
  });

  const absence = await Promise.all([
    editorTopic.confirmAbsence((f) => parseBody(f)?.clientMutationId === 'mut-viewer-1', ABSENCE_WINDOW_MS),
    viewerTopic.confirmAbsence((f) => parseBody(f)?.clientMutationId === 'mut-viewer-1', ABSENCE_WINDOW_MS),
  ]);
  if (absence.every((r) => r.absent)) {
    pass('VIEWER muta -> NO se difunde en el topic', `no aparecio en ${ABSENCE_WINDOW_MS}ms en ningun topic`);
  } else {
    fail('VIEWER muta -> NO se difunde en el topic', 'VIOLACION: la mutacion del VIEWER se propago igual');
  }

  try {
    const errFrame = await viewerErrors.waitFor(
      (f) => parseBody(f)?.clientMutationId === 'mut-viewer-1',
      MESSAGE_TIMEOUT_MS,
      '403 FORBIDDEN_ROLE para mut-viewer-1',
    );
    const body = parseBody(errFrame);
    if (body?.status === 403 && body?.code === 'FORBIDDEN_ROLE') {
      pass('VIEWER recibe 403 FORBIDDEN_ROLE', JSON.stringify(body));
    } else {
      fail('VIEWER recibe 403 FORBIDDEN_ROLE', `llego un mensaje con forma inesperada: ${errFrame.body}`);
    }
  } catch (e) {
    fail('VIEWER recibe 403 FORBIDDEN_ROLE', e.message);
  }

  // Paso 6: el rechazo NO cierra la sesion del VIEWER.
  viewerClient.publish({
    destination: `/app/diagram/${DIAGRAM_ID}/mutate`,
    body: moveClassBody('mut-viewer-2', 1, 1),
  });
  try {
    await viewerErrors.waitFor((f) => parseBody(f)?.clientMutationId === 'mut-viewer-2', MESSAGE_TIMEOUT_MS, 'segundo 403 (mut-viewer-2)');
    pass('VIEWER sigue vivo: responde a un segundo intento', 'llego un segundo 403, la sesion no se cerro');
  } catch (e) {
    fail('VIEWER sigue vivo: responde a un segundo intento', e.message);
  }

  editorClient.publish({
    destination: `/app/diagram/${DIAGRAM_ID}/mutate`,
    body: moveClassBody('mut-editor-2', 500, 500),
  });
  try {
    await viewerTopic.waitFor((f) => parseBody(f)?.clientMutationId === 'mut-editor-2', MESSAGE_TIMEOUT_MS, 'broadcast mut-editor-2 visto por el VIEWER');
    pass('VIEWER sigue vivo: sigue recibiendo el broadcast del EDITOR', 'llego mut-editor-2 despues del rechazo');
  } catch (e) {
    fail('VIEWER sigue vivo: sigue recibiendo el broadcast del EDITOR', e.message);
  }

  if (viewerCloseState.closedUnexpectedly) {
    fail('el WebSocket del VIEWER no se cerro solo', 'el socket se cerro durante la prueba, antes de lo esperado');
  } else {
    pass('el WebSocket del VIEWER no se cerro solo', 'sigue abierto');
  }

  await Promise.all([editorClient.deactivate(), viewerClient.deactivate()]);
  finish();
}

main().catch((e) => {
  console.error('ERROR INESPERADO:', e);
  process.exitCode = 1;
});
