#!/usr/bin/env node
// ModelCollab :: humo STOMP del chat grupal por proyecto (CU19, etapa 2).
//
// Prueba, contra un backend ya levantado, que el chat de un proyecto:
//   (a) llega a dos miembros suscritos: el MISMO mensaje, en el MISMO orden, con seq creciente;
//   (b) es idempotente: reenviar el mismo clientMessageId no duplica;
//   (c) admite a un VIEWER: puede suscribirse y enviar;
//   (d) rechaza a un NO miembro: su SUBSCRIBE y su SEND se descartan y recibe el error por
//       /user/queue/errors (NOT_PROJECT_MEMBER);
//   (e) no se puede espiar con comodines: SUBSCRIBE a /topic/projects/** o /topic/** de un
//       usuario autenticado se rechaza (INVALID_PAYLOAD) y no recibe nada;
//   (f) un diagramId de OTRO proyecto se rechaza con INVALID_PAYLOAD y no se difunde.
//
// ---------------------------------------------------------------------------------------------
// CONFIGURACION (todas obligatorias, solo por variables de entorno; no hay valores por defecto)
//   MC_HTTP_BASE               p. ej. http://localhost:8080
//   MC_WS_URL                  p. ej. ws://localhost:8080/ws
//   MC_PROJECT_ID              proyecto del chat
//   MC_FOREIGN_DIAGRAM_ID      diagrama que pertenece a OTRO proyecto
//   MC_MEMBER_EMAIL / MC_MEMBER_PASSWORD       miembro del proyecto (OWNER o EDITOR)
//   MC_VIEWER_EMAIL / MC_VIEWER_PASSWORD       miembro VIEWER del mismo proyecto
//   MC_OUTSIDER_EMAIL / MC_OUTSIDER_PASSWORD   usuario SIN membresia en MC_PROJECT_ID
// Opcional: MC_DEBUG=1 loguea los frames STOMP (con el header Authorization redactado).
//
// Exit code 0 si todos los pasos pasan; 1 si alguno falla; 2 si falta configuracion.
// Los tokens JWT nunca se imprimen.
//
// ---------------------------------------------------------------------------------------------
// DATOS QUE NECESITA DE scripts/seed-dev.sql
//   - Proyecto 44444444-4444-4444-4444-444444444444 (Barberia)          -> MC_PROJECT_ID
//   - editor@modelcollab.dev (EDITOR del proyecto)                     -> MC_MEMBER_*
//   - viewer@modelcollab.dev (VIEWER del proyecto)                     -> MC_VIEWER_*
//     (los usuarios del seed comparten la contrasena documentada en seed-dev.sql)
//   - Un usuario SIN membresia en ese proyecto                         -> MC_OUTSIDER_*
//   - Un diagrama de OTRO proyecto                                     -> MC_FOREIGN_DIAGRAM_ID
//   Los dos ultimos NO existen hoy en seed-dev.sql: hay que agregar un usuario, un segundo
//   proyecto (propiedad de ese usuario, con su membresia OWNER) y un diagrama en ese proyecto.
//
// Limite de frecuencia: el backend acepta 20 mensajes por minuto y usuario. Una corrida manda
// 3 aceptados del miembro y 1 del VIEWER; se puede repetir sin esperar.
//
// Ejemplo (PowerShell):
//   $env:MC_HTTP_BASE="http://localhost:8080"; $env:MC_WS_URL="ws://localhost:8080/ws"; ...
//   node scripts/chat-smoke.mjs

import { Client } from '@stomp/stompjs';
import WebSocket from 'ws';

const REQUIRED = [
  'MC_HTTP_BASE',
  'MC_WS_URL',
  'MC_PROJECT_ID',
  'MC_FOREIGN_DIAGRAM_ID',
  'MC_MEMBER_EMAIL',
  'MC_MEMBER_PASSWORD',
  'MC_VIEWER_EMAIL',
  'MC_VIEWER_PASSWORD',
  'MC_OUTSIDER_EMAIL',
  'MC_OUTSIDER_PASSWORD',
];
const missing = REQUIRED.filter((k) => !process.env[k]);
if (missing.length > 0) {
  console.error(`Falta configuracion: ${missing.join(', ')}`);
  console.error('Ver el comentario al inicio de scripts/chat-smoke.mjs.');
  process.exit(2);
}

const HTTP_BASE = process.env.MC_HTTP_BASE;
const WS_URL = process.env.MC_WS_URL;
const PROJECT_ID = process.env.MC_PROJECT_ID;
const FOREIGN_DIAGRAM_ID = process.env.MC_FOREIGN_DIAGRAM_ID;
const MEMBER = { label: 'miembro', email: process.env.MC_MEMBER_EMAIL, password: process.env.MC_MEMBER_PASSWORD };
const VIEWER = { label: 'viewer', email: process.env.MC_VIEWER_EMAIL, password: process.env.MC_VIEWER_PASSWORD };
const OUTSIDER = { label: 'ajeno', email: process.env.MC_OUTSIDER_EMAIL, password: process.env.MC_OUTSIDER_PASSWORD };

const TOPIC = `/topic/projects/${PROJECT_ID}/chat`;
const SEND_DEST = `/app/project/${PROJECT_ID}/chat`;
const ERRORS = '/user/queue/errors';

const CONNECT_TIMEOUT_MS = 5000;
const MESSAGE_TIMEOUT_MS = 5000;
const ABSENCE_WINDOW_MS = 2500;
// El canal entrante de Spring no garantiza el orden entre frames de una sesion: se deja un
// margen para que las suscripciones queden registradas antes de enviar.
const SUBSCRIBE_SETTLE_MS = 500;
const DEBUG = process.env.MC_DEBUG === '1';

// Prefijo unico por corrida: los clientMessageId no chocan con corridas anteriores.
const RUN = `smoke-${Date.now().toString(36)}`;

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

function redact(text) {
  return String(text).replace(/Bearer\s+[^\s"']+/gi, 'Bearer ***');
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
    throw new Error(`HTTP ${res.status} al loguear ${creds.email}: ${redact(text.slice(0, 300))}`);
  }
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`respuesta no-JSON al loguear ${creds.email}`);
  }
  if (!json.token) {
    throw new Error(`falta "token" en la respuesta de login de ${creds.email}`);
  }
  return json.token;
}

// Cola de mensajes de una suscripcion, con espera por predicado y timeout (igual que en
// stomp-smoke.mjs), mas acceso al historial para comparar orden y contar duplicados.
function createInbox(label) {
  const messages = [];
  const waiters = [];

  function push(frame) {
    if (DEBUG) console.log(`  [frame:${label}] ${frame.headers.destination} :: ${redact(frame.body)}`);
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

  const all = () => messages.slice();
  return { push, waitFor, confirmAbsence, all };
}

function connectStomp(token, label) {
  return new Promise((resolve, reject) => {
    const client = new Client({
      webSocketFactory: () => new WebSocket(WS_URL),
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 0,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
      // stompjs loguea el frame CONNECT completo: se redacta el token.
      debug: DEBUG ? (msg) => console.log(`  [stomp:${label}] ${redact(msg)}`) : () => {},
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
      reject(new Error(`frame ERROR de STOMP conectando (${label}): ${frame.headers?.message ?? ''}`.trim()));
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

// --------------------------------------------------------------------------- utilidades chat

const isChatMessage = (f) => parseBody(f)?.event === 'CHAT_MESSAGE';
const chatMsg = (f) => parseBody(f)?.message;
const withClientId = (id) => (f) => isChatMessage(f) && chatMsg(f)?.clientMessageId === id;
const ofRun = (f) => isChatMessage(f) && String(chatMsg(f)?.clientMessageId ?? '').startsWith(RUN);

function sendChat(client, clientMessageId, text, extra = {}) {
  client.publish({
    destination: SEND_DEST,
    body: JSON.stringify({ clientMessageId, text, ...extra }),
  });
}

const errorFor = (predicate) => (f) => {
  const e = parseBody(f);
  return e != null && typeof e.code === 'string' && predicate(e);
};

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

// -------------------------------------------------------------------------------------- main

async function main() {
  console.log('== ModelCollab :: humo del chat por proyecto (CU19) ==');
  console.log(`HTTP=${HTTP_BASE}  WS=${WS_URL}  proyecto=${PROJECT_ID}  corrida=${RUN}`);
  console.log('');

  // ---------------------------------------------------------------- login + conexiones
  const tokens = {};
  for (const u of [MEMBER, VIEWER, OUTSIDER]) {
    try {
      tokens[u.label] = await login(u);
      pass(`login REST (${u.label})`, 'token recibido');
    } catch (e) {
      fail(`login REST (${u.label})`, e.message);
    }
  }
  if (Object.keys(tokens).length < 3) {
    fail('conexiones STOMP', 'no se intentan: falta al menos un login');
    finish();
    return;
  }

  let member;
  let viewer;
  let outsider;
  try {
    [member, viewer, outsider] = await Promise.all([
      connectStomp(tokens.miembro, 'miembro'),
      connectStomp(tokens.viewer, 'viewer'),
      connectStomp(tokens.ajeno, 'ajeno'),
    ]);
    pass('CONNECT STOMP de los tres usuarios', 'conectados');
  } catch (e) {
    fail('CONNECT STOMP de los tres usuarios', e.message);
    finish();
    return;
  }

  const memberTopic = createInbox('miembro:topic');
  const memberErrors = createInbox('miembro:errors');
  const viewerTopic = createInbox('viewer:topic');
  const viewerErrors = createInbox('viewer:errors');
  const outsiderTopic = createInbox('ajeno:topic');
  const outsiderErrors = createInbox('ajeno:errors');
  const spyProjects = createInbox('ajeno:/topic/projects/**');
  const spyAll = createInbox('ajeno:/topic/**');

  // Primero las colas de errores, para no perder los rechazos de las suscripciones siguientes.
  member.subscribe(ERRORS, memberErrors.push);
  viewer.subscribe(ERRORS, viewerErrors.push);
  outsider.subscribe(ERRORS, outsiderErrors.push);
  await sleep(SUBSCRIBE_SETTLE_MS);

  member.subscribe(TOPIC, memberTopic.push);
  viewer.subscribe(TOPIC, viewerTopic.push);
  outsider.subscribe(TOPIC, outsiderTopic.push);
  outsider.subscribe('/topic/projects/**', spyProjects.push);
  outsider.subscribe('/topic/**', spyAll.push);
  await sleep(SUBSCRIBE_SETTLE_MS);

  // ------------------------------------------------- (d) SUBSCRIBE del no miembro rechazado
  try {
    const e = await outsiderErrors.waitFor(
      errorFor((x) => x.destination === TOPIC),
      MESSAGE_TIMEOUT_MS,
      'error del SUBSCRIBE al topic del chat',
    );
    const body = parseBody(e);
    if (body.code === 'NOT_PROJECT_MEMBER') {
      pass('(d) no miembro: SUBSCRIBE rechazado', `${body.status} ${body.code}`);
    } else {
      fail('(d) no miembro: SUBSCRIBE rechazado', `codigo inesperado ${body.code}`);
    }
  } catch (err) {
    fail('(d) no miembro: SUBSCRIBE rechazado', err.message);
  }

  // ------------------------------------------- (e) SUBSCRIBE con comodines rechazados
  for (const pattern of ['/topic/projects/**', '/topic/**']) {
    try {
      const e = await outsiderErrors.waitFor(
        errorFor((x) => x.destination === pattern),
        MESSAGE_TIMEOUT_MS,
        `error del SUBSCRIBE a ${pattern}`,
      );
      const body = parseBody(e);
      if (body.code === 'INVALID_PAYLOAD') {
        pass(`(e) SUBSCRIBE a ${pattern} rechazado`, `${body.status} ${body.code}`);
      } else {
        fail(`(e) SUBSCRIBE a ${pattern} rechazado`, `codigo inesperado ${body.code}`);
      }
    } catch (err) {
      fail(`(e) SUBSCRIBE a ${pattern} rechazado`, err.message);
    }
  }

  // ------------------------------------- (a) dos miembros reciben lo mismo en el mismo orden
  const ids = [1, 2, 3].map((n) => `${RUN}-a${n}`);
  for (const [i, id] of ids.entries()) {
    sendChat(member, id, `humo ${RUN}: mensaje ${i + 1}`);
  }
  try {
    await Promise.all(
      ids.flatMap((id) => [
        memberTopic.waitFor(withClientId(id), MESSAGE_TIMEOUT_MS, `${id} en el miembro`),
        viewerTopic.waitFor(withClientId(id), MESSAGE_TIMEOUT_MS, `${id} en el viewer`),
      ]),
    );
    const enMiembro = memberTopic.all().filter((f) => ids.includes(chatMsg(f)?.clientMessageId)).map(chatMsg);
    const enViewer = viewerTopic.all().filter((f) => ids.includes(chatMsg(f)?.clientMessageId)).map(chatMsg);
    const mismoOrden =
      enMiembro.length === enViewer.length &&
      enMiembro.every((m, i) => m.id === enViewer[i].id && m.seq === enViewer[i].seq);
    const seqs = enMiembro.map((m) => m.seq);
    const creciente = seqs.every((s, i) => i === 0 || s > seqs[i - 1]);
    const ordenDeEnvio = enMiembro.map((m) => m.clientMessageId).join() === ids.join();
    if (mismoOrden && creciente && ordenDeEnvio) {
      pass('(a) dos miembros: mismo mensaje, mismo orden, seq creciente', `seq ${seqs.join(' < ')}`);
    } else {
      fail(
        '(a) dos miembros: mismo mensaje, mismo orden, seq creciente',
        `mismoOrden=${mismoOrden} creciente=${creciente} ordenDeEnvio=${ordenDeEnvio} seq=[${seqs.join(',')}]`,
      );
    }
  } catch (err) {
    fail('(a) dos miembros: mismo mensaje, mismo orden, seq creciente', err.message);
  }

  // --------------------------------------------------------------- (b) idempotencia
  sendChat(member, ids[0], `humo ${RUN}: reintento`);
  await sleep(ABSENCE_WINDOW_MS);
  {
    const enMiembro = memberTopic.all().filter(withClientId(ids[0])).length;
    const enViewer = viewerTopic.all().filter(withClientId(ids[0])).length;
    if (enMiembro === 1 && enViewer === 1) {
      pass('(b) idempotencia: el reintento no se duplica', 'una sola copia en cada cliente');
    } else {
      fail('(b) idempotencia: el reintento no se duplica', `copias: miembro=${enMiembro} viewer=${enViewer}`);
    }
  }

  // --------------------------------------------------------------- (c) el VIEWER escribe
  const viewerId = `${RUN}-c1`;
  sendChat(viewer, viewerId, `humo ${RUN}: escribe el viewer`);
  try {
    await Promise.all([
      memberTopic.waitFor(withClientId(viewerId), MESSAGE_TIMEOUT_MS, 'mensaje del viewer en el miembro'),
      viewerTopic.waitFor(withClientId(viewerId), MESSAGE_TIMEOUT_MS, 'mensaje del viewer en el viewer'),
    ]);
    pass('(c) VIEWER: se suscribe y envia', 'su mensaje llego a los dos');
  } catch (err) {
    const rechazo = viewerErrors.all().map(parseBody).find((e) => e?.clientMutationId === viewerId);
    fail('(c) VIEWER: se suscribe y envia', rechazo ? `rechazado: ${rechazo.code}` : err.message);
  }

  // ------------------------------------------------------- (d) SEND del no miembro rechazado
  const outsiderId = `${RUN}-d1`;
  sendChat(outsider, outsiderId, `humo ${RUN}: intruso`);
  try {
    const e = await outsiderErrors.waitFor(
      errorFor((x) => x.clientMutationId === outsiderId),
      MESSAGE_TIMEOUT_MS,
      'error del SEND del no miembro',
    );
    const body = parseBody(e);
    const llego = await memberTopic.confirmAbsence(withClientId(outsiderId), ABSENCE_WINDOW_MS);
    if (body.code === 'NOT_PROJECT_MEMBER' && llego.absent) {
      pass('(d) no miembro: SEND descartado', `${body.status} ${body.code}, nadie lo recibio`);
    } else {
      fail('(d) no miembro: SEND descartado', `codigo=${body.code} difundido=${!llego.absent}`);
    }
  } catch (err) {
    fail('(d) no miembro: SEND descartado', err.message);
  }

  // ------------------------------------------------- (f) diagramId de otro proyecto
  const foreignId = `${RUN}-f1`;
  sendChat(member, foreignId, `humo ${RUN}: diagrama ajeno`, { diagramId: FOREIGN_DIAGRAM_ID });
  try {
    const e = await memberErrors.waitFor(
      errorFor((x) => x.clientMutationId === foreignId),
      MESSAGE_TIMEOUT_MS,
      'error por diagramId de otro proyecto',
    );
    const body = parseBody(e);
    const llego = await viewerTopic.confirmAbsence(withClientId(foreignId), ABSENCE_WINDOW_MS);
    if (body.code === 'INVALID_PAYLOAD' && llego.absent) {
      pass('(f) diagramId de otro proyecto: INVALID_PAYLOAD', 'no se difundio');
    } else {
      fail('(f) diagramId de otro proyecto: INVALID_PAYLOAD', `codigo=${body.code} difundido=${!llego.absent}`);
    }
  } catch (err) {
    fail('(f) diagramId de otro proyecto: INVALID_PAYLOAD', err.message);
  }

  // ------------------------------------- (d)/(e) el no miembro no vio nada en toda la corrida
  {
    const vistos = {
      topic: outsiderTopic.all().filter(isChatMessage).length,
      'projects/**': spyProjects.all().length,
      '**': spyAll.all().length,
    };
    const total = Object.values(vistos).reduce((a, b) => a + b, 0);
    if (total === 0) {
      pass('(d)(e) el no miembro no recibio ningun mensaje', 'ni por el topic exacto ni por comodines');
    } else {
      fail('(d)(e) el no miembro no recibio ningun mensaje', JSON.stringify(vistos));
    }
  }

  // Ningun miembro deberia haber recibido errores inesperados (salvo el de (f)).
  const inesperados = memberErrors.all().map(parseBody).filter((e) => e?.clientMutationId !== foreignId);
  if (inesperados.length === 0 && viewerErrors.all().length === 0) {
    pass('sin errores inesperados para los miembros', '');
  } else {
    fail(
      'sin errores inesperados para los miembros',
      [...inesperados, ...viewerErrors.all().map(parseBody)].map((e) => `${e?.code}@${e?.destination}`).join(', '),
    );
  }

  // Sanity: cuenta los mensajes de esta corrida que vio el miembro (3 de (a) + 1 de (c)).
  const deLaCorrida = memberTopic.all().filter(ofRun).length;
  if (deLaCorrida === 4) {
    pass('total de mensajes de la corrida en el miembro', '4 (3 + 1 del viewer)');
  } else {
    fail('total de mensajes de la corrida en el miembro', `se esperaban 4, llegaron ${deLaCorrida}`);
  }

  await Promise.all([member.deactivate(), viewer.deactivate(), outsider.deactivate()]);
  finish();
}

main().catch((e) => {
  console.error('ERROR INESPERADO:', redact(e?.stack ?? e));
  process.exitCode = 1;
});
