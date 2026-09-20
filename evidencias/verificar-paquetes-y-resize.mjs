// Las tres operaciones de PAQUETE y RESIZE_CLASS, contra el servidor de
// verdad y con dos sesiones (mismo arnes que verificar-metodos.mjs).
//
// Qué contesta y qué NO contesta:
//
//   SÍ  — que ADD_PACKAGE / UPDATE_PACKAGE / DELETE_PACKAGE y RESIZE_CLASS viajan,
//         que la OTRA sesión las recibe difundidas, que el estado del servidor
//         queda como corresponde, que ADD_PACKAGE y RESIZE_CLASS NO piden
//         candado (alta nueva / ultimo-que-escribe-gana como MOVE_CLASS) y que
//         UPDATE/DELETE_PACKAGE SÍ respetan el candado de CU06, por el id del
//         PAQUETE (no por clase).
//   NO  — que la pantalla las dibuje. Eso se ve con dos pestañas abiertas y
//         nadie más que una persona lo puede comprobar. Acá se verifica el
//         cable, no el pixel.
//
// Uso:  node verificar-paquetes-y-resize.mjs        (el backend tiene que estar en :8080)

const BASE = process.env.BASE ?? "http://localhost:8080";
const WS = BASE.replace("http", "ws") + "/ws";
const NUL = String.fromCharCode(0);

const sello = Date.now();
let fallos = 0;
function comprobar(ok, texto, detalle = "") {
  console.log(`${ok ? "  OK  " : " FALLA"}  ${texto}${detalle ? "\n        " + detalle : ""}`);
  if (!ok) fallos++;
}

async function json(ruta, opciones = {}) {
  const r = await fetch(BASE + ruta, opciones);
  const texto = await r.text();
  try { return { status: r.status, cuerpo: texto ? JSON.parse(texto) : null }; }
  catch { return { status: r.status, cuerpo: texto }; }
}

function frame(comando, cabeceras, cuerpo = "") {
  const lineas = Object.entries(cabeceras).map(([k, v]) => `${k}:${v}`);
  return `${comando}\n${lineas.join("\n")}\n\n${cuerpo}${NUL}`;
}

function parsear(texto) {
  const corte = texto.indexOf("\n\n");
  const cabeza = texto.slice(0, corte).split("\n");
  const cabeceras = {};
  for (const linea of cabeza.slice(1)) {
    const i = linea.indexOf(":");
    if (i > 0) cabeceras[linea.slice(0, i)] = linea.slice(i + 1);
  }
  return { comando: cabeza[0], cabeceras, cuerpo: texto.slice(corte + 2).replace(/\0$/, "") };
}

function sesion(token, diagramId, nombre) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS);
    const recibidos = [];
    let avisar = null;

    ws.onopen = () => ws.send(frame("CONNECT", {
      "accept-version": "1.2", host: "localhost", Authorization: `Bearer ${token}`,
    }));

    ws.onmessage = (ev) => {
      const f = parsear(String(ev.data));
      if (f.comando === "CONNECTED") {
        ws.send(frame("SUBSCRIBE", { id: "sala", destination: `/topic/diagrams/${diagramId}` }));
        ws.send(frame("SUBSCRIBE", { id: "locks", destination: "/user/queue/locks" }));
        ws.send(frame("SUBSCRIBE", { id: "errs", destination: "/user/queue/errors" }));
        setTimeout(() => resolve(api), 600);
        return;
      }
      if (f.comando === "MESSAGE") {
        recibidos.push(f);
        if (avisar) avisar();
      }
      if (f.comando === "ERROR") reject(new Error(`${nombre}: ${f.cuerpo}`));
    };
    ws.onerror = (e) => reject(new Error(`${nombre}: no abrió el WebSocket: ${e.message}`));

    const api = {
      nombre,
      ws,
      recibidos,
      mutar(op, targetId, payload) {
        ws.send(frame("SEND", { destination: `/app/diagram/${diagramId}/mutate` },
          JSON.stringify({ op, targetId, clientMutationId: crypto.randomUUID(), payload })));
      },
      candado(targetId, action) {
        ws.send(frame("SEND", { destination: `/app/diagram/${diagramId}/lock` },
          JSON.stringify({ action, targetId })));
      },
      esperar(predicado, ms = 8000) {
        return new Promise((ok) => {
          const ya = recibidos.find(predicado);
          if (ya) return ok(ya);
          const reloj = setTimeout(() => { avisar = null; ok(null); }, ms);
          avisar = () => {
            const hallado = recibidos.find(predicado);
            if (hallado) { clearTimeout(reloj); avisar = null; ok(hallado); }
          };
        });
      },
    };
  });
}

const cuerpoDe = (f) => JSON.parse(f.cuerpo);

async function conCandado(sesion, targetId, hacer) {
  sesion.candado(targetId, "ACQUIRE");
  await sesion.esperar((f) => {
    try { const c = cuerpoDe(f); return c.event === "LOCK_ACQUIRED" && c.targetId === targetId; }
    catch { return false; }
  }, 5000);
  hacer();
  await new Promise((r) => setTimeout(r, 400));
  sesion.candado(targetId, "RELEASE");
}
const esOp = (op) => (f) => { try { return cuerpoDe(f).op === op; } catch { return false; } };

// ------------------------------------------------------------------ la corrida

const alta = await json("/api/v1/auth/register", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    email: `paquetes-${sello}@prueba.local`, password: "clave-de-prueba-12345",
    fullName: "Prueba Paquetes",
  }),
});
if (alta.status >= 300) { console.error("no se pudo registrar:", alta.status, alta.cuerpo); process.exit(1); }
const token = alta.cuerpo.token;
const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

const proyecto = await json("/api/v1/projects", {
  method: "POST", headers: auth, body: JSON.stringify({ name: "Paquetes " + sello }),
});
const diagrama = await json(`/api/v1/projects/${proyecto.cuerpo.id}/diagrams`, {
  method: "POST", headers: auth, body: JSON.stringify({ name: "Clases" }),
});
const diagramId = diagrama.cuerpo.id;
console.log(`\nDiagrama ${diagramId}\n`);

const a = await sesion(token, diagramId, "pestaña A");
const b = await sesion(token, diagramId, "pestaña B");

const claseId = crypto.randomUUID();
const paqueteId = crypto.randomUUID();

console.log("1. Una clase para redimensionar y meter en un paquete");
a.mutar("ADD_CLASS", null, {
  id: claseId, name: "Cuenta", visibility: "PUBLIC", isAbstract: false,
  position: { x: 100, y: 100 }, width: 240, height: 180, attributes: [], methods: [],
});
comprobar(await b.esperar(esOp("ADD_CLASS")) !== null, "B recibe la clase nueva");

console.log("\n2. ADD_PACKAGE (sin candado -- el paquete todavia no existe)");
a.mutar("ADD_PACKAGE", null, { id: paqueteId, name: "Dominio", classIds: [claseId] });
const llegoAlta = await b.esperar(esOp("ADD_PACKAGE"));
comprobar(llegoAlta !== null, "B recibe ADD_PACKAGE difundido");
if (llegoAlta) {
  const p = cuerpoDe(llegoAlta).payload;
  comprobar(p.classIds?.length === 1 && p.classIds[0] === claseId, "con la clase ya adentro", JSON.stringify(p));
}

console.log("\n3. El candado de CU06 sobre el PAQUETE (no sobre una clase)");
const otroCorreo = `paquetes-dos-${sello}@prueba.local`;
const otra = await json("/api/v1/auth/register", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: otroCorreo, password: "clave-de-prueba-12345", fullName: "Colega" }),
});
await json(`/api/v1/projects/${proyecto.cuerpo.id}/members`, {
  method: "POST", headers: auth, body: JSON.stringify({ email: otroCorreo, role: "EDITOR" }),
});
const c = await sesion(otra.cuerpo.token, diagramId, "colega C");

a.candado(paqueteId, "ACQUIRE");
comprobar(await a.esperar((f) => {
  try { const x = cuerpoDe(f); return x.event === "LOCK_ACQUIRED" && x.targetId === paqueteId; }
  catch { return false; }
}, 5000) !== null, "A toma el candado del paquete");

c.candado(paqueteId, "ACQUIRE");
const negado = await c.esperar((f) => { try { return cuerpoDe(f).event === "LOCK_DENIED"; } catch { return false; } }, 5000);
comprobar(negado !== null, "otro usuario pide el mismo paquete y se lo NIEGAN",
  negado ? JSON.stringify(cuerpoDe(negado)) : "no llegó ningún LOCK_DENIED");
a.candado(paqueteId, "RELEASE");

console.log("\n4. UPDATE_PACKAGE (reemplazo completo)");
await conCandado(a, paqueteId, () => a.mutar("UPDATE_PACKAGE", paqueteId, {
  id: paqueteId, name: "Dominio principal", classIds: [],
}));
const llegoCambio = await b.esperar(esOp("UPDATE_PACKAGE"));
comprobar(llegoCambio !== null, "B recibe UPDATE_PACKAGE");
if (llegoCambio) {
  const p = cuerpoDe(llegoCambio).payload;
  comprobar(p.name === "Dominio principal" && p.classIds.length === 0,
    "es un REEMPLAZO completo (la clase se quitó, no quedó pegada)", JSON.stringify(p));
}

console.log("\n5. RESIZE_CLASS (SIN candado -- ultimo-que-escribe-gana, como MOVE_CLASS)");
const negadoResize = await (async () => {
  // No se pide candado antes: si el servidor lo exigiera, esto volveria un
  // error por /user/queue/errors en vez de difundirse.
  a.mutar("RESIZE_CLASS", claseId, { width: 320, height: 220 });
  return b.esperar(esOp("RESIZE_CLASS"));
})();
comprobar(negadoResize !== null, "B recibe RESIZE_CLASS difundido sin que A pidiera candado");
if (negadoResize) {
  const r = cuerpoDe(negadoResize).payload;
  comprobar(r.width === 320 && r.height === 220, "con el ancho/alto nuevos", JSON.stringify(r));
}

console.log("\n6. DELETE_PACKAGE");
await conCandado(a, paqueteId, () => a.mutar("DELETE_PACKAGE", paqueteId, {}));
comprobar(await b.esperar(esOp("DELETE_PACKAGE")) !== null, "B recibe DELETE_PACKAGE");

console.log("\n7. Lo que quedó en el SERVIDOR, que es lo único que sobrevive a un F5");
await new Promise((r) => setTimeout(r, 800));
const foto = await json(`/api/v1/diagrams/${diagramId}/snapshot`, { headers: auth });
const clase = foto.cuerpo?.currentState?.classes?.find((x) => x.id === claseId);
comprobar(clase !== undefined, "la clase está en el estado canónico");
comprobar(clase?.width === 320 && clase?.height === 220,
  "con el tamaño redimensionado persistido", JSON.stringify({ width: clase?.width, height: clase?.height }));
comprobar((foto.cuerpo?.currentState?.packages ?? []).length === 0,
  "y sin paquetes: el alta, el cambio y la baja se aplicaron de verdad",
  JSON.stringify(foto.cuerpo?.currentState?.packages));

if (fallos > 0 || process.env.MC_DUMP) {
  console.log("");
  console.log("--- lo que recibio cada sesion, para no adivinar ---");
  for (const s of [a, b]) {
    console.log("  " + s.nombre + ":");
    for (const f of s.recibidos) {
      console.log("    " + f.cabeceras.destination + " :: " + f.cuerpo.slice(0, 220));
    }
  }
}

a.ws.close();
b.ws.close();
c.ws.close();
console.log(fallos === 0
  ? "\nTODO VERDE: paquetes (alta/edicion/baja) y redimensionar viajan, se difunden y persisten.\n"
  : `\n${fallos} comprobación(es) en rojo.\n`);
process.exit(fallos === 0 ? 0 : 1);
