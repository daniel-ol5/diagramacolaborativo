// Las tres operaciones de METODO, contra el servidor de verdad y con dos sesiones.
//
// Qué contesta y qué NO contesta:
//
//   SÍ  — que ADD_METHOD / UPDATE_METHOD / DELETE_METHOD viajan por el mismo camino que las
//         de atributo, que la OTRA sesión las recibe difundidas, que el estado del servidor
//         queda como corresponde, y que el candado de CU06 sobre el id del método se respeta.
//   NO  — que la pantalla las dibuje. Eso se ve con dos pestañas abiertas y nadie más que una
//         persona lo puede comprobar. Acá se verifica el cable, no el pixel.
//
// Uso:  node verificar-metodos.mjs        (el backend tiene que estar en :8080)
//       BASE=http://localhost:8091 node verificar-metodos.mjs   (a través de Nginx)

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

// ------------------------------------------------------------------ STOMP a mano

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

/** Una sesión = una pestaña. Se suscribe al topic y al canal privado de locks. */
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

/**
 * Toma el candado, muta y suelta — el orden exacto que sigue la interfaz.
 *
 * La primera version de este arnes mutaba sin pedir el candado y el servidor
 * contesto "409 LOCK_REQUIRED: la operacion ADD_METHOD exige poseer el lock".
 * O sea que el fallo era del arnes y no del sistema; de paso queda probado que
 * esa exigencia se aplica de verdad a las tres operaciones de metodo.
 */
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
    email: `metodos-${sello}@prueba.local`, password: "clave-de-prueba-12345",
    fullName: "Prueba Metodos",
  }),
});
if (alta.status >= 300) { console.error("no se pudo registrar:", alta.status, alta.cuerpo); process.exit(1); }
const token = alta.cuerpo.token;
const auth = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

const proyecto = await json("/api/v1/projects", {
  method: "POST", headers: auth, body: JSON.stringify({ name: "Metodos " + sello }),
});
const diagrama = await json(`/api/v1/projects/${proyecto.cuerpo.id}/diagrams`, {
  method: "POST", headers: auth, body: JSON.stringify({ name: "Clases" }),
});
const diagramId = diagrama.cuerpo.id;
console.log(`\nDiagrama ${diagramId}\n`);

// Dos sesiones del mismo usuario: es exactamente "dos pestañas abiertas".
const a = await sesion(token, diagramId, "pestaña A");
const b = await sesion(token, diagramId, "pestaña B");

const claseId = crypto.randomUUID();
const metodoId = crypto.randomUUID();

console.log("1. Una clase para colgarle métodos");
a.mutar("ADD_CLASS", null, {
  id: claseId, name: "Cuenta", visibility: "PUBLIC", isAbstract: false,
  position: { x: 100, y: 100 }, width: 240, height: 180, attributes: [], methods: [],
});
comprobar(await b.esperar(esOp("ADD_CLASS")) !== null, "B recibe la clase nueva");

console.log("\n2. ADD_METHOD");
// El candado va sobre la CLASE: el metodo todavia no existe.
await conCandado(a, claseId, () => a.mutar("ADD_METHOD", claseId, {
  id: metodoId, name: "depositar", returnType: "void", visibility: "PUBLIC",
  parameters: [{ name: "monto", type: "BigDecimal" }],
}));
const llegoAlta = await b.esperar(esOp("ADD_METHOD"));
comprobar(llegoAlta !== null, "B recibe ADD_METHOD difundido");
if (llegoAlta) {
  const m = cuerpoDe(llegoAlta);
  comprobar(m.targetId === claseId, "viaja colgado de la clase, no suelto", `targetId ${m.targetId}`);
  comprobar(m.payload?.parameters?.length === 1, "y con sus parámetros",
    JSON.stringify(m.payload?.parameters));
}

console.log("\n3. El candado de CU06 sobre el método");
// Dos pestañas del mismo usuario NO sirven para esto: el candado es por
// persona, asi que la segunda pestaña de uno mismo lo recibe igual. Hace falta
// un segundo usuario de verdad, agregado al proyecto como editor.
const otroCorreo = `metodos-dos-${sello}@prueba.local`;
const otra = await json("/api/v1/auth/register", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: otroCorreo, password: "clave-de-prueba-12345", fullName: "Colega" }),
});
await json(`/api/v1/projects/${proyecto.cuerpo.id}/members`, {
  method: "POST", headers: auth, body: JSON.stringify({ email: otroCorreo, role: "EDITOR" }),
});
const c = await sesion(otra.cuerpo.token, diagramId, "colega C");

a.candado(metodoId, "ACQUIRE");
comprobar(await a.esperar((f) => {
  try { const x = cuerpoDe(f); return x.event === "LOCK_ACQUIRED" && x.targetId === metodoId; }
  catch { return false; }
}, 5000) !== null, "A toma el candado del método");

c.candado(metodoId, "ACQUIRE");
const negado = await c.esperar((f) => { try { return cuerpoDe(f).event === "LOCK_DENIED"; } catch { return false; } }, 5000);
comprobar(negado !== null, "otro usuario pide el mismo y se lo NIEGAN: se bloquea por el id del método",
  negado ? JSON.stringify(cuerpoDe(negado)) : "no llegó ningún LOCK_DENIED");

const otroMetodo = crypto.randomUUID();
c.candado(otroMetodo, "ACQUIRE");
const otorgado = await c.esperar((f) => {
  try { const x = cuerpoDe(f); return x.event === "LOCK_ACQUIRED" && x.targetId === otroMetodo; }
  catch { return false; }
}, 5000);
// Si el candado fuera de la clase entera esto tambien se negaria, y dos
// personas no podrian tocar dos metodos distintos de la misma clase a la vez.
comprobar(otorgado !== null, "y SÍ le dan el de otro método: el bloqueo es por método, no por clase");
a.candado(metodoId, "RELEASE");
c.candado(otroMetodo, "RELEASE");

console.log("\n4. UPDATE_METHOD");
await conCandado(a, metodoId, () => a.mutar("UPDATE_METHOD", metodoId, {
  id: metodoId, name: "depositarEnCuenta", returnType: "boolean", visibility: "PROTECTED",
  parameters: [{ name: "monto", type: "BigDecimal" }, { name: "concepto", type: "String" }],
}));
const llegoCambio = await b.esperar(esOp("UPDATE_METHOD"));
comprobar(llegoCambio !== null, "B recibe UPDATE_METHOD");
if (llegoCambio) {
  const m = cuerpoDe(llegoCambio).payload;
  comprobar(m.name === "depositarEnCuenta" && m.returnType === "boolean" && m.parameters.length === 2,
    "es un REEMPLAZO completo, no un parche", `${m.visibility} ${m.name}(${m.parameters.length} params): ${m.returnType}`);
}

console.log("\n5. DELETE_METHOD");
await conCandado(a, metodoId, () => a.mutar("DELETE_METHOD", metodoId, {}));
comprobar(await b.esperar(esOp("DELETE_METHOD")) !== null, "B recibe DELETE_METHOD");

console.log("\n6. Lo que quedó en el SERVIDOR, que es lo único que sobrevive a un F5");
await new Promise((r) => setTimeout(r, 800));
const foto = await json(`/api/v1/diagrams/${diagramId}/snapshot`, { headers: auth });
const clase = foto.cuerpo?.currentState?.classes?.find((x) => x.id === claseId);
comprobar(clase !== undefined, "la clase está en el estado canónico");
comprobar(clase?.methods?.length === 0,
  "y sin métodos: el alta, el cambio y la baja se aplicaron de verdad",
  JSON.stringify(clase?.methods));

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
  ? "\nTODO VERDE: las tres operaciones de método viajan, se difunden y persisten.\n"
  : `\n${fallos} comprobación(es) en rojo.\n`);
process.exit(fallos === 0 ? 0 : 1);
