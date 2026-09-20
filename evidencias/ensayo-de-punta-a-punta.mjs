// ENSAYO DE PUNTA A PUNTA, con los tiempos medidos.
//
// Cada pieza de ModelCollab está probada por separado. La cadena completa —registrarse, modelar,
// invitar, editar de a dos, validar, generar, descargar el ZIP, ARRANCARLO y crear un registro—
// no la recorrió nadie nunca. Y este proyecto ya falló cuatro veces en las costuras: el
// @ManyToOne(mappedBy), el prefijo de rutas del móvil, la correspondencia ranura/DTO y el DTO que
// no dejaba crear un Dueno. **Las cuatro con las dos piezas correctas por separado.**
//
// SE HACE COMO USUARIO, NO COMO QUIEN LO CONSTRUYÓ: usuario nuevo, proyecto desde cero, diagrama
// vacío, y todo por los mismos endpoints que usa el navegador. Nada de datos precargados ni de
// atajos que sólo conoce quien escribió el servidor.
//
// LO QUE NO CUBRE, y hay que decirlo: la pantalla. No hay forma de hacer clic desde acá. Esto
// recorre el cable exactamente como lo recorre la web —mismos endpoints, mismo orden, mismos
// mensajes STOMP— pero que los botones estén donde uno los busca lo tiene que mirar una persona.
//
// Uso:  node ensayo-de-punta-a-punta.mjs
//       MC_CON_ZIP=1 node ensayo-de-punta-a-punta.mjs   (además descomprime, compila y arranca
//                                                        el backend generado: +3 a 5 minutos)

import { spawn } from "node:child_process";
import { mkdtempSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const BASE = process.env.BASE ?? "http://localhost:8080";
const WS = BASE.replace("http", "ws") + "/ws";
const NUL = String.fromCharCode(0);
const CON_ZIP = process.env.MC_CON_ZIP === "1";
const sello = Date.now();

let fallos = 0;
const tiempos = [];
const friccion = [];

function ok(cond, texto, detalle = "") {
  console.log(`   ${cond ? "OK  " : "FALLA"}  ${texto}${detalle ? "  — " + detalle : ""}`);
  if (!cond) fallos++;
  return cond;
}

async function paso(nombre, fn) {
  console.log(`\n${nombre}`);
  const t0 = Date.now();
  const r = await fn();
  const ms = Date.now() - t0;
  tiempos.push({ nombre, ms });
  console.log(`   (${(ms / 1000).toFixed(1)} s)`);
  return r;
}

function anotarFriccion(texto) {
  friccion.push(texto);
  console.log(`   ~ fricción: ${texto}`);
}

// --------------------------------------------------------------- HTTP y STOMP

async function api(ruta, opciones = {}) {
  const r = await fetch(BASE + ruta, opciones);
  const texto = await r.text();
  let cuerpo = null;
  try { cuerpo = texto ? JSON.parse(texto) : null; } catch { cuerpo = texto; }
  return { status: r.status, cuerpo, texto, headers: r.headers };
}

const conAuth = (token, extra = {}) => ({
  Authorization: `Bearer ${token}`, "Content-Type": "application/json", ...extra,
});

function frame(comando, cabeceras, cuerpo = "") {
  return `${comando}\n${Object.entries(cabeceras).map(([k, v]) => `${k}:${v}`).join("\n")}\n\n${cuerpo}${NUL}`;
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
        setTimeout(() => resolve(api2), 600);
        return;
      }
      if (f.comando === "MESSAGE") { recibidos.push(f); if (avisar) avisar(); }
      if (f.comando === "ERROR") reject(new Error(`${nombre}: ${f.cuerpo}`));
    };
    ws.onerror = (e) => reject(new Error(`${nombre}: ${e.message}`));

    const api2 = {
      nombre, ws, recibidos,
      mutar(op, targetId, payload) {
        ws.send(frame("SEND", { destination: `/app/diagram/${diagramId}/mutate` },
          JSON.stringify({ op, targetId, clientMutationId: crypto.randomUUID(), payload })));
      },
      candado(targetId, action) {
        ws.send(frame("SEND", { destination: `/app/diagram/${diagramId}/lock` },
          JSON.stringify({ action, targetId })));
      },
      cursor(x, y) {
        ws.send(frame("SEND", { destination: `/app/diagram/${diagramId}/cursor` },
          JSON.stringify({ x, y, selectedId: null })));
      },
      esperar(pred, ms = 8000) {
        return new Promise((res) => {
          const ya = recibidos.find(pred);
          if (ya) return res(ya);
          const reloj = setTimeout(() => { avisar = null; res(null); }, ms);
          avisar = () => {
            const h = recibidos.find(pred);
            if (h) { clearTimeout(reloj); avisar = null; res(h); }
          };
        });
      },
    };
  });
}

const cuerpoDe = (f) => { try { return JSON.parse(f.cuerpo); } catch { return {}; } };
const esOp = (op) => (f) => cuerpoDe(f).op === op;
const esEvento = (ev) => (f) => cuerpoDe(f).event === ev;

async function conCandado(s, targetId, hacer) {
  s.candado(targetId, "ACQUIRE");
  await s.esperar((f) => { const c = cuerpoDe(f); return c.event === "LOCK_ACQUIRED" && c.targetId === targetId; }, 5000);
  hacer();
  await new Promise((r) => setTimeout(r, 250));
  s.candado(targetId, "RELEASE");
}

// ------------------------------------------------------------------- el dominio

const id = () => crypto.randomUUID();
const CLASES = {};
for (const n of ["Alumno", "Curso", "Profesor", "Inscripcion"]) CLASES[n] = id();

function clase(nombre, x, y) {
  return {
    id: CLASES[nombre], name: nombre, visibility: "PUBLIC", isAbstract: false,
    position: { x, y }, width: 240, height: 180, attributes: [], methods: [],
  };
}

function atributo(nombre, tipo, extra = {}) {
  return {
    id: id(), name: nombre, type: tipo, visibility: "PRIVATE",
    isPrimaryKey: false, isNullable: true, isUnique: false, ...extra,
  };
}

// =================================================================== la corrida

console.log(`\nENSAYO DE PUNTA A PUNTA — ${new Date().toLocaleString("es-AR")}`);
console.log(`Servidor: ${BASE}${CON_ZIP ? "  (con arranque del ZIP)" : "  (sin arranque del ZIP)"}`);

const correoA = `colegio-${sello}@prueba.local`;
const correoB = `colega-${sello}@prueba.local`;
const CLAVE = "clave-de-prueba-12345";

let tokenA, tokenB, proyectoId, diagramId, a, b;

await paso("1. Registrarse desde cero (usuario nuevo, nada sembrado)", async () => {
  const r = await api("/api/v1/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: correoA, password: CLAVE, fullName: "Daniel Olmos" }),
  });
  ok(r.status === 201 || r.status === 200, `registro devuelve ${r.status}`);
  ok(Boolean(r.cuerpo?.token), "y ya viene con sesión iniciada: no hay que loguearse aparte");
  tokenA = r.cuerpo.token;
});

await paso("2. Crear un proyecto y un diagrama vacío", async () => {
  const p = await api("/api/v1/projects", {
    method: "POST", headers: conAuth(tokenA),
    body: JSON.stringify({ name: "Colegio", description: "Ensayo de punta a punta" }),
  });
  ok(p.status === 201, `proyecto creado (${p.status})`);
  proyectoId = p.cuerpo.id;

  const d = await api(`/api/v1/projects/${proyectoId}/diagrams`, {
    method: "POST", headers: conAuth(tokenA), body: JSON.stringify({ name: "Academico" }),
  });
  ok(d.status === 201, `diagrama creado (${d.status})`);
  diagramId = d.cuerpo.id;
});

await paso("3. Modelar el colegio a mano (4 clases, 9 atributos, 3 relaciones)", async () => {
  a = await sesion(tokenA, diagramId, "A");

  a.mutar("ADD_CLASS", null, clase("Alumno", 80, 80));
  a.mutar("ADD_CLASS", null, clase("Curso", 420, 80));
  a.mutar("ADD_CLASS", null, clase("Profesor", 80, 400));
  a.mutar("ADD_CLASS", null, clase("Inscripcion", 420, 400));
  await new Promise((r) => setTimeout(r, 700));

  const atributos = [
    ["Alumno", atributo("id", "UUID", { isPrimaryKey: true, isNullable: false })],
    ["Alumno", atributo("nombre", "VARCHAR", { length: 120, isNullable: false })],
    ["Alumno", atributo("matricula", "VARCHAR", { length: 20, isNullable: false, isUnique: true })],
    ["Curso", atributo("id", "UUID", { isPrimaryKey: true, isNullable: false })],
    ["Curso", atributo("nombre", "VARCHAR", { length: 100, isNullable: false })],
    ["Curso", atributo("cupo", "INTEGER", { isNullable: false })],
    ["Profesor", atributo("id", "UUID", { isPrimaryKey: true, isNullable: false })],
    ["Profesor", atributo("nombre", "VARCHAR", { length: 120, isNullable: false })],
    ["Inscripcion", atributo("id", "UUID", { isPrimaryKey: true, isNullable: false })],
    ["Inscripcion", atributo("fecha", "DATE", { isNullable: false })],
  ];
  for (const [clase_, attr] of atributos) {
    await conCandado(a, CLASES[clase_], () => a.mutar("ADD_ATTRIBUTE", CLASES[clase_], attr));
  }

  const relaciones = [
    { id: id(), sourceClassId: CLASES.Alumno, targetClassId: CLASES.Inscripcion,
      type: "ASSOCIATION", owningSide: "TARGET",
      sourceMultiplicity: "1..1", targetMultiplicity: "0..*", isNavigable: true, waypoints: [] },
    { id: id(), sourceClassId: CLASES.Curso, targetClassId: CLASES.Inscripcion,
      type: "ASSOCIATION", owningSide: "TARGET",
      sourceMultiplicity: "1..1", targetMultiplicity: "0..*", isNavigable: true, waypoints: [] },
    { id: id(), sourceClassId: CLASES.Profesor, targetClassId: CLASES.Curso,
      type: "ASSOCIATION", owningSide: "TARGET",
      sourceMultiplicity: "1..1", targetMultiplicity: "0..*", isNavigable: true, waypoints: [] },
  ];
  for (const r of relaciones) a.mutar("ADD_RELATIONSHIP", null, r);
  await new Promise((r) => setTimeout(r, 900));

  const foto = await api(`/api/v1/diagrams/${diagramId}/snapshot`, { headers: conAuth(tokenA) });
  const m = foto.cuerpo?.currentState;
  ok(m?.classes?.length === 4, `el servidor tiene 4 clases`, `tiene ${m?.classes?.length}`);
  ok(m?.relationships?.length === 3, `y 3 relaciones`, `tiene ${m?.relationships?.length}`);
  const totalAttr = (m?.classes ?? []).reduce((n, c) => n + c.attributes.length, 0);
  ok(totalAttr === 10, `y 10 atributos`, `tiene ${totalAttr}`);
});

await paso("4. Invitar a un colaborador y que acepte", async () => {
  const reg = await api("/api/v1/auth/register", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: correoB, password: CLAVE, fullName: "Colega de Prueba" }),
  });
  tokenB = reg.cuerpo.token;

  const inv = await api(`/api/v1/projects/${proyectoId}/invitations`, {
    method: "POST", headers: conAuth(tokenA), body: JSON.stringify({ role: "EDITOR" }),
  });
  ok(inv.status === 201 || inv.status === 200, `invitación creada (${inv.status})`);
  const token = inv.cuerpo?.token ?? inv.cuerpo?.invitationToken;
  if (!token) { anotarFriccion(`la invitación no devolvió token: ${inv.texto.slice(0, 120)}`); return; }

  const mirar = await api(`/api/v1/invitations/${token}`);
  ok(mirar.status === 200, "el enlace se puede MIRAR sin estar logueado (entra en frío)");

  const aceptar = await api(`/api/v1/invitations/${token}/accept`, {
    method: "POST", headers: conAuth(tokenB),
  });
  ok(aceptar.status === 200 || aceptar.status === 201, `y aceptarla estando logueado (${aceptar.status})`);
});

await paso("5. Editar los dos a la vez: candado y cursores", async () => {
  b = await sesion(tokenB, diagramId, "B");

  a.candado(CLASES.Alumno, "ACQUIRE");
  ok(await a.esperar(esEvento("LOCK_ACQUIRED"), 5000) !== null, "A toma el candado de Alumno");
  ok(await b.esperar(esEvento("LOCK_ACQUIRED"), 4000) !== null, "y B LO VE: la caja se marca en su pantalla");

  b.candado(CLASES.Alumno, "ACQUIRE");
  ok(await b.esperar(esEvento("LOCK_DENIED"), 5000) !== null, "B pide el mismo y se lo niegan");
  a.candado(CLASES.Alumno, "RELEASE");

  b.cursor(300, 250);
  ok(await a.esperar(esEvento("USER_CURSOR"), 5000) !== null, "A ve el cursor de B");

  await conCandado(b, CLASES.Curso, () =>
    b.mutar("RENAME_CLASS", CLASES.Curso, { name: "Materia" }));
  const eco = await a.esperar(esOp("RENAME_CLASS"), 6000);
  ok(eco !== null, "B renombra Curso a Materia y A lo recibe en el acto");
});

await paso("6. La vista Entidad-Relación con los nombres físicos reales", async () => {
  const r = await api(`/api/v1/diagrams/${diagramId}/schema-preview`, { headers: conAuth(tokenA) });
  ok(r.status === 200, `schema-preview responde ${r.status}`);
  const tablas = (r.cuerpo?.tables ?? []).map((t) => t.name);
  ok(tablas.includes("alumno") && tablas.includes("materia"),
    "las tablas llevan el nombre físico", tablas.join(", "));
  const insc = r.cuerpo.tables.find((t) => t.name === "inscripcion");
  const fks = (insc?.columns ?? []).filter((c) => c.references).map((c) => `${c.name}->${c.references}`);
  ok(fks.length === 2, "y las claves foráneas sintetizadas están del lado correcto", fks.join(", "));
});

await paso("7. Validar antes de generar", async () => {
  const r = await api(`/api/v1/diagrams/${diagramId}/validate`, { headers: conAuth(tokenA) });
  ok(r.status === 200, `validate responde ${r.status}`);
  ok(r.cuerpo?.valid === true, "el diagrama es válido",
    JSON.stringify(r.cuerpo?.errors ?? []).slice(0, 200));
  if ((r.cuerpo?.warnings ?? []).length) {
    console.log(`   · ${r.cuerpo.warnings.length} advertencia(s), que no impiden generar`);
  }
});

let zip = null;
await paso("8. Generar y DESCARGAR el ZIP de verdad", async () => {
  const r = await fetch(`${BASE}/api/v1/diagrams/${diagramId}/generate-backend`, {
    method: "POST", headers: { Authorization: `Bearer ${tokenA}` },
  });
  ok(r.status === 200, `generate-backend responde ${r.status}`);
  const disp = r.headers.get("content-disposition");
  ok(Boolean(disp && disp.includes("attachment")),
    "viene como descarga con nombre de archivo", disp ?? "(sin Content-Disposition)");
  zip = Buffer.from(await r.arrayBuffer());
  ok(zip.length > 10000, `y pesa algo razonable`, `${(zip.length / 1024).toFixed(0)} KB`);
  ok(zip[0] === 0x50 && zip[1] === 0x4b, "y es un ZIP de verdad (firma PK)");
});

await paso("9. Exportar el XMI y generar la app móvil", async () => {
  const x = await fetch(`${BASE}/api/v1/diagrams/${diagramId}/export-xmi`, {
    headers: { Authorization: `Bearer ${tokenA}` },
  });
  if (x.status === 404) {
    anotarFriccion("no encontré la ruta de exportar XMI; hay que confirmar cuál es");
  } else {
    const texto = await x.text();
    ok(x.status === 200, `export-xmi responde ${x.status}`);
    ok(texto.includes("modelcollab-root-package"),
      "y el XMI cuelga del paquete raíz (la vuelta 3)");
  }

  const m = await fetch(`${BASE}/api/v1/diagrams/${diagramId}/generate-mobile?apiUrl=http://10.0.2.2:8080`, {
    method: "POST", headers: { Authorization: `Bearer ${tokenA}` },
  });
  ok(m.status === 200, `generate-mobile responde ${m.status}`);
  const apk = Buffer.from(await m.arrayBuffer());
  ok(apk.length > 5000, `el ZIP de la app pesa ${(apk.length / 1024).toFixed(0)} KB`);
});

if (CON_ZIP && zip) {
  await paso("10. Descomprimir el ZIP, compilarlo y ARRANCARLO", async () => {
    const dir = mkdtempSync(join(tmpdir(), "ensayo-zip-"));
    const archivo = join(dir, "backend.zip");
    writeFileSync(archivo, zip);

    const correr = (cmd, args, cwd, ms) => new Promise((res) => {
      const p = spawn(cmd, args, { cwd, shell: true });
      let salida = "";
      p.stdout.on("data", (d) => (salida += d));
      p.stderr.on("data", (d) => (salida += d));
      const reloj = setTimeout(() => { p.kill(); res({ code: -1, salida }); }, ms);
      p.on("exit", (code) => { clearTimeout(reloj); res({ code, salida }); });
    });

    // Expand-Archive y no `tar`: es lo que hay seguro en un Windows limpio, y es lo que va a
    // usar cualquiera que reciba el ZIP. `tar` existe en Windows 10+ pero falla en silencio
    // segun desde donde se lo invoque — y un paso que falla sin decirlo es peor que uno que no
    // existe.
    const desc = await correr("powershell", [
      "-NoProfile", "-Command",
      `Expand-Archive -LiteralPath 'backend.zip' -DestinationPath . -Force`,
    ], dir, 120000);
    if (desc.code !== 0) anotarFriccion(`descomprimir fallo: ${desc.salida.slice(-200)}`);

    const dentro = readdirSync(dir);
    const raiz = dentro.map((n) => join(dir, n))
      .find((r) => existsSync(join(r, "pom.xml"))) ?? dir;
    ok(existsSync(join(raiz, "pom.xml")),
      "el ZIP trae un proyecto Maven listo para abrir",
      existsSync(join(raiz, "pom.xml")) ? raiz : `lo que salio: ${dentro.join(", ")}`);
    if (!existsSync(join(raiz, "pom.xml"))) return;

    // ESTO ES LO QUE VE EL USUARIO al descomprimir: si tiene que bucear dos carpetas para
    // encontrar el pom, conviene saberlo antes de la defensa.
    console.log(`   · al descomprimir aparece: ${dentro.join(", ")}`);

    const build = await correr(".\\mvnw.cmd", ["-q", "-B", "package", "-DskipTests"], raiz, 900000);
    if (!ok(build.code === 0, "y COMPILA tal como se descarga",
        build.code === 0 ? "" : build.salida.slice(-300))) return;

    // --- y ahora lo que de verdad importa: que ARRANQUE y acepte un alta -------------------
    //
    // Compilar no dice nada sobre si la base lo acepta. El defecto del DuenoRequestDTO
    // compilaba perfecto y hacia imposible crear la entidad; sin este tramo, el ensayo daria
    // verde sobre un ZIP que el usuario no puede usar.
    const base = "ensayo_" + Date.now();
    const PSQL = "C:/Program Files/PostgreSQL/18/bin/psql.exe";
    const conPsql = (sql) => correr(`"${PSQL}"`,
      ["-U", "postgres", "-d", "postgres", "-c", `"${sql}"`], dir, 30000);

    process.env.PGPASSWORD = "12345678";
    await conPsql(`CREATE DATABASE ${base}`);

    const jarDir = join(raiz, "target");
    const jar = readdirSync(jarDir)
      .filter((n) => n.endsWith(".jar") && !n.endsWith("-plain.jar"))[0];
    if (!ok(Boolean(jar), "el empaquetado dejo un jar")) return;

    const servidor = spawn("java", ["-jar", join(jarDir, jar)], {
      cwd: raiz,
      env: {
        ...process.env,
        SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:5432/${base}`,
        SPRING_DATASOURCE_USERNAME: "postgres",
        SPRING_DATASOURCE_PASSWORD: "12345678",
        SERVER_PORT: "8099",
      },
    });

    // Se espera la linea en SU salida, no que el puerto conteste: un puerto ocupado por otro
    // servidor daria verde sin que este proyecto haya llegado a levantar. Ya nos paso.
    const arranco = await new Promise((res) => {
      let visto = "";
      const reloj = setTimeout(() => res(false), 120000);
      const mirar = (d) => {
        visto += d;
        if (visto.includes("Started ")) { clearTimeout(reloj); res(true); }
      };
      servidor.stdout.on("data", mirar);
      servidor.stderr.on("data", mirar);
    });
    ok(arranco, "ARRANCA contra un PostgreSQL vacio y crea sus tablas");

    if (arranco) {
      const alta = await fetch("http://localhost:8099/api/alumnos", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nombre: "Ana Perez", matricula: "A-0001" }),
      });
      const creado = await alta.text();
      ok(alta.status === 201, `y ACEPTA UN ALTA por su API (${alta.status})`, creado.slice(0, 160));
    }

    servidor.kill();
    await new Promise((r) => setTimeout(r, 1500));
    await conPsql(`DROP DATABASE IF EXISTS ${base} WITH (FORCE)`);
  });
}

// ------------------------------------------------------------------ el informe

if (a) a.ws.close();
if (b) b.ws.close();

console.log("\n\n=========== TIEMPOS, PARA EL GUION DE LA DEMOSTRACIÓN ===========\n");
let total = 0;
for (const t of tiempos) {
  total += t.ms;
  console.log(`  ${(t.ms / 1000).toFixed(1).padStart(6)} s   ${t.nombre}`);
}
console.log(`  ${"-".repeat(6)}`);
console.log(`  ${(total / 1000).toFixed(1).padStart(6)} s   TOTAL`);

if (friccion.length) {
  console.log("\n=========== FRICCIÓN ANOTADA ===========\n");
  friccion.forEach((f) => console.log(`  · ${f}`));
}

console.log(fallos === 0
  ? "\nTODO VERDE: la cadena completa se recorre de punta a punta.\n"
  : `\n${fallos} comprobación(es) en rojo.\n`);
process.exit(fallos === 0 ? 0 : 1);
