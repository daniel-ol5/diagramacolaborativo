#!/usr/bin/env node
// ModelCollab :: verificacion determinista del criterio del Dia 7
// ("las 6 relaciones del canonico se renderizan distinguibles entre si").
//
// A proposito NO mira una captura de pantalla: un PNG de Chrome headless
// resulto flaky para este chequeo (el compositor a veces no pinta las
// aristas aunque el DOM ya las tenga con geometria y estilo correctos,
// verificado con getBoundingClientRect) — reintentar hasta que la imagen
// "se vea bien" es el mismo error que la evidencia falsa de modo avion del
// Dia 1 (spike-voz/evidencia.sh). La asercion primaria es sobre el DOM
// real; el PNG que ve un humano es una ilustracion, no la prueba.
//
// Uso (con `npm run dev` corriendo en localhost:5173):
//   node scripts/verify-relationships.mjs
// Exit 0 si las 6 tipos de relacion, en los 4 diagramas, tienen el
// marcador/trazo que les corresponde; distinto de 0 si falta alguno.

import { readFileSync } from 'node:fs';
import path from 'node:path';
import puppeteer from 'puppeteer-core';

const WEB_URL = process.env.MC_WEB_URL ?? 'http://localhost:5173';
const CHROME_PATH =
  process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const FIXTURES_DIR = path.resolve(
  import.meta.dirname,
  '../../backend/src/test/resources',
);

const DIAGRAMS = [
  ['barberia', 'reference-diagrams/barberia.json'],
  ['veterinaria', 'reference-diagrams/veterinaria.json'],
  ['inventario', 'reference-diagrams/inventario.json'],
  ['edge-cases', 'edge-cases.json'],
];

// El contrato visual de §7/§8 (tabla que dio [planner] para el Dia 7).
// ASSOCIATION y MANY_TO_MANY comparten trazo (linea solida sin marcador):
// lo que las distingue no es el marcador sino que MANY_TO_MANY siempre
// trae multiplicidad en los dos extremos, por eso ese tipo declara
// `requireBothMultiplicities`.
const EXPECTED = {
  ASSOCIATION: { markerStart: false, markerEnd: false, dashed: false },
  MANY_TO_MANY: { markerStart: false, markerEnd: false, dashed: false, requireBothMultiplicities: true },
  AGGREGATION: { diamond: 'uml-diamond-hollow' },
  COMPOSITION: { diamond: 'uml-diamond-filled' },
  INHERITANCE: { markerStart: false, markerEnd: 'uml-triangle-hollow', dashed: false },
  DEPENDENCY: { markerStart: false, markerEnd: 'uml-arrow-open', dashed: true },
};

const results = [];
const pass = (name, detail) => results.push({ name, ok: true, detail: detail ?? '' });
const fail = (name, detail) => results.push({ name, ok: false, detail: detail ?? '' });

function wholeEnd(relationship) {
  const owningSide = relationship.owningSide ?? 'SOURCE';
  return owningSide === 'SOURCE' ? 'target' : 'source';
}

function markerId(urlAttr) {
  if (!urlAttr) return null;
  const m = /url\(#([^)]+)\)/.exec(urlAttr);
  return m ? m[1] : null;
}

function parsePathPoints(d) {
  return d
    .split(/[ML]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((pair) => {
      const [x, y] = pair.split(',').map(Number);
      return { x, y };
    });
}

async function verifyDiagram(page, diagramKey, model) {
  await page.select('#diagram-select', diagramKey);
  await page.waitForFunction(
    (expectedCount) => document.querySelectorAll('.react-flow__edge').length >= expectedCount,
    { timeout: 5000 },
    model.relationships.length,
  );

  for (const relationship of model.relationships) {
    const label = `[${diagramKey}] ${relationship.type} (${relationship.id.slice(0, 8)})`;

    const edgeInfo = await page.evaluate((relId) => {
      const g = document.querySelector(`.react-flow__edge[data-testid="rf__edge-${relId}"]`);
      if (!g) return null;
      const p = g.querySelector('path.react-flow__edge-path');
      if (!p) return null;
      return {
        d: p.getAttribute('d'),
        markerStart: p.getAttribute('marker-start'),
        markerEnd: p.getAttribute('marker-end'),
        dasharray: getComputedStyle(p).strokeDasharray,
        labels: [...g.querySelectorAll('text.uml-edge-label')].map((t) => t.textContent ?? ''),
      };
    }, relationship.id);

    if (!edgeInfo) {
      fail(label, 'no se encontro el <path> de la arista en el DOM');
      continue;
    }

    const expected = EXPECTED[relationship.type];
    let ok = true;
    const problems = [];

    if ('diamond' in expected) {
      const end = wholeEnd(relationship);
      const attr = end === 'source' ? edgeInfo.markerStart : edgeInfo.markerEnd;
      const otherAttr = end === 'source' ? edgeInfo.markerEnd : edgeInfo.markerStart;
      if (markerId(attr) !== expected.diamond) {
        ok = false;
        problems.push(`esperaba ${expected.diamond} en marker-${end === 'source' ? 'start' : 'end'}, hubo "${attr}"`);
      }
      if (otherAttr) {
        ok = false;
        problems.push(`marker inesperado en el otro extremo: "${otherAttr}"`);
      }
    } else {
      const gotStart = markerId(edgeInfo.markerStart);
      const gotEnd = markerId(edgeInfo.markerEnd);
      const wantStart = expected.markerStart || null;
      const wantEnd = expected.markerEnd || null;
      if (gotStart !== wantStart) {
        ok = false;
        problems.push(`marker-start esperado ${wantStart ?? '(ninguno)'}, hubo ${gotStart ?? '(ninguno)'}`);
      }
      if (gotEnd !== wantEnd) {
        ok = false;
        problems.push(`marker-end esperado ${wantEnd ?? '(ninguno)'}, hubo ${gotEnd ?? '(ninguno)'}`);
      }
      const isDashed = edgeInfo.dasharray && edgeInfo.dasharray !== 'none';
      if (Boolean(isDashed) !== Boolean(expected.dashed)) {
        ok = false;
        problems.push(`stroke-dasharray esperado ${expected.dashed ? 'presente' : 'ausente'}, hubo "${edgeInfo.dasharray}"`);
      }
    }

    if (expected.requireBothMultiplicities) {
      const text = edgeInfo.labels.join(' | ');
      if (!relationship.sourceMultiplicity || !text.includes(relationship.sourceMultiplicity)) {
        ok = false;
        problems.push(`falta la multiplicidad de origen (${relationship.sourceMultiplicity}) en las etiquetas: "${text}"`);
      }
      if (!relationship.targetMultiplicity || !text.includes(relationship.targetMultiplicity)) {
        ok = false;
        problems.push(`falta la multiplicidad de destino (${relationship.targetMultiplicity}) en las etiquetas: "${text}"`);
      }
    }

    const waypoints = relationship.waypoints ?? [];
    if (waypoints.length > 0) {
      const points = parsePathPoints(edgeInfo.d);
      for (const wp of waypoints) {
        const found = points.some((p) => p.x === wp.x && p.y === wp.y);
        if (!found) {
          ok = false;
          problems.push(`el path no pasa por el waypoint (${wp.x},${wp.y}): d="${edgeInfo.d}"`);
        }
      }
    }

    if (ok) {
      pass(label, waypoints.length > 0 ? `${waypoints.length} waypoint(s) verificados` : 'ok');
    } else {
      fail(label, problems.join('; '));
    }
  }
}

async function main() {
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: 'new',
    args: ['--no-sandbox'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1000 });
  await page.goto(WEB_URL, { waitUntil: 'networkidle0' });

  for (const [diagramKey, fixturePath] of DIAGRAMS) {
    const model = JSON.parse(readFileSync(path.join(FIXTURES_DIR, fixturePath), 'utf8'));
    await verifyDiagram(page, diagramKey, model);
  }

  await browser.close();

  console.log('');
  console.log('== resumen ==');
  const width = Math.max(20, ...results.map((r) => r.name.length));
  for (const r of results) {
    console.log(`${r.ok ? 'PASA ' : 'FALLA'} ${r.name.padEnd(width)}  ${r.detail}`);
  }
  const failed = results.filter((r) => !r.ok);
  console.log('');
  console.log(`${results.length - failed.length}/${results.length} relaciones OK`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

main().catch((e) => {
  console.error('ERROR INESPERADO:', e);
  process.exitCode = 1;
});
