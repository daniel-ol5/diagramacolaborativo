// ¿ngrok sirve para reemplazar a Cloudflare? La única razón para cambiar es la DIRECCIÓN FIJA.
//
// El túnel rápido de Cloudflare ya está medido y funciona, pero sortea un nombre nuevo cada vez
// que arranca. Eso deja dos fragilidades para el 23:
//   - si algo se reinicia, TODOS los enlaces repartidos mueren, invitaciones incluidas;
//   - la app móvil guarda la dirección, y cambiarla significa tipearla en el teléfono con el
//     tribunal mirando.
//
// ngrok en su plan gratuito promete UN dominio fijo por cuenta. Esto lo comprueba en vez de
// creerle a la página de precios.
//
// CRITERIO DE DECISIÓN, para que no sea gusto:
//   Se cambia SÓLO si pasa (1) la dirección fija y (2) el WebSocket entero, incluidos los 70 s
//   de silencio. Si falla cualquiera de los dos, nos quedamos con Cloudflare, que ya está
//   medido. No se cambia algo probado por algo mejor en teoría a dos días de la defensa.
//
// NO expone el backend real: del otro lado hay un servidor de juguete sin datos, igual que en
// las dos mediciones anteriores.
//
// Uso:  MC_NGROK_DOMINIO=algo-algo.ngrok-free.app node probar-ngrok.mjs
//       (hace falta haber corrido antes:  ngrok.exe config add-authtoken <token>)

import http from "node:http";
import { spawn } from "node:child_process";
import { WebSocketServer, WebSocket } from "ws";

const NGROK = process.env.MC_NGROK ?? "./ngrok.exe";
const DOMINIO = process.env.MC_NGROK_DOMINIO;
const PUERTO = 8123;
const MARCA = "modelcollab-ngrok-" + Date.now();

if (!DOMINIO) {
  console.error(`
Falta MC_NGROK_DOMINIO.

Es el dominio fijo que ngrok le asigna a la cuenta. Se ve en
https://dashboard.ngrok.com/domains y tiene la forma algo-algo-algo.ngrok-free.app

Sin dominio fijo este test no tiene sentido: lo único que justifica cambiar de
Cloudflare a ngrok es justamente que la dirección no cambie.
`);
  process.exit(2);
}

let fallos = 0;
function comprobar(ok, texto, detalle = "") {
  console.log(`${ok ? "  OK  " : " FALLA"}  ${texto}${detalle ? "\n        " + detalle : ""}`);
  if (!ok) fallos++;
  return ok;
}

// ------------------------------------------------- el servidor de juguete

const servidor = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("pagina:" + MARCA);
});
const wss = new WebSocketServer({ server: servidor, path: "/ws" });
wss.on("connection", (s) => s.on("message", (d) => s.send("eco:" + d.toString())));
await new Promise((r) => servidor.listen(PUERTO, r));
console.log(`\nServidor de juguete en :${PUERTO} (sin datos del proyecto)\n`);

// ------------------------------------------------- levantar y bajar el túnel

function abrirTunel() {
  return new Promise((resolve, reject) => {
    const p = spawn(NGROK, ["http", String(PUERTO), "--url", DOMINIO, "--log", "stdout"],
      { cwd: process.cwd() });
    let visto = "";
    const reloj = setTimeout(
      () => reject(new Error("ngrok no levantó en 45 s:\n" + visto.slice(-600))), 45000);
    const mirar = (b) => {
      visto += b.toString();
      const m = visto.match(/url=(https:\/\/[^\s"]+)/);
      if (m) { clearTimeout(reloj); resolve({ proceso: p, url: m[1] }); }
      if (/ERR_NGROK_\d+/.test(visto)) {
        clearTimeout(reloj);
        reject(new Error(visto.match(/ERR_NGROK_\d+[\s\S]{0,200}/)[0]));
      }
    };
    p.stdout.on("data", mirar);
    p.stderr.on("data", mirar);
  });
}

const cerrar = (t) => new Promise((r) => { t.proceso.on("exit", r); t.proceso.kill(); setTimeout(r, 3000); });

// ------------------------------------------------- 1. la dirección fija

console.log("1. La dirección: ¿es la misma al bajar y volver a levantar?");
const primera = await abrirTunel();
console.log(`   primera vez:  ${primera.url}`);
await cerrar(primera);
await new Promise((r) => setTimeout(r, 4000));
const segunda = await abrirTunel();
console.log(`   segunda vez:  ${segunda.url}`);

comprobar(primera.url === segunda.url,
  "la dirección NO cambia entre reinicios — es la única razón para cambiar de túnel");

const tunel = segunda;
await new Promise((r) => setTimeout(r, 3000));

// ------------------------------------------------- 2. HTTPS

console.log("\n2. HTTPS con certificado válido");
try {
  const r = await fetch(tunel.url, { redirect: "follow" });
  const texto = await r.text();
  comprobar(r.status === 200, `responde 200`, `status ${r.status}`);
  comprobar(texto.includes(MARCA), "y lo que vuelve es lo que sirve MI servidor");
} catch (e) {
  comprobar(false, "el GET por HTTPS falló", String(e));
}

// ------------------------------------------------- 3. WebSocket

console.log("\n3. La actualización a WebSocket a través del túnel");
const wsUrl = tunel.url.replace("https://", "wss://") + "/ws";
await new Promise((listo) => {
  const c = new WebSocket(wsUrl);
  const reloj = setTimeout(() => {
    comprobar(false, "el WebSocket no conectó en 25 s", wsUrl);
    try { c.terminate(); } catch {}
    listo();
  }, 25000);
  c.on("open", () => {
    comprobar(true, "el handshake se completó: el túnel negocia el upgrade");
    c.send("hola-" + MARCA);
  });
  c.on("message", (d) => {
    comprobar(d.toString() === "eco:hola-" + MARCA, "y los frames viajan en las dos direcciones");
    clearTimeout(reloj); c.close(); listo();
  });
  c.on("error", (e) => { comprobar(false, "el WebSocket dio error", String(e)); clearTimeout(reloj); listo(); });
});

// ------------------------------------------------- 4. los 70 segundos

console.log("\n4. La conexión se sostiene sin tráfico (lo que mata a una sala inactiva)");
await new Promise((listo) => {
  const c = new WebSocket(wsUrl);
  const desde = Date.now();
  const reloj = setTimeout(() => {
    comprobar(c.readyState === WebSocket.OPEN,
      `sigue abierta después de ${Math.round((Date.now() - desde) / 1000)} s sin mandar nada`);
    try { c.close(); } catch {}
    listo();
  }, 70000);
  c.on("error", (e) => { comprobar(false, "se cayó sola antes de los 70 s", String(e)); clearTimeout(reloj); listo(); });
  c.on("close", () => {
    if (Date.now() - desde < 68000) {
      comprobar(false, `la cerró el intermediario a los ${Math.round((Date.now() - desde) / 1000)} s`);
      clearTimeout(reloj); listo();
    }
  });
});

await cerrar(tunel);
servidor.close();
wss.close();

console.log(fallos === 0
  ? "\nPASA LOS DOS CRITERIOS: dirección fija y WebSocket entero. Se puede cambiar.\n"
  : `\n${fallos} comprobación(es) en rojo — NOS QUEDAMOS CON CLOUDFLARE.\n`);
process.exit(fallos === 0 ? 0 : 1);
