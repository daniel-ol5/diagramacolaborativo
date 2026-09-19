// ¿Un túnel de Cloudflare deja pasar la ACTUALIZACIÓN A WEBSOCKET?
//
// Es la única pregunta que importa del camino del túnel, y la única que no se puede dar por
// supuesta leyendo la documentación del proveedor. Un túnel que sirve páginas perfectamente
// puede no negociar el upgrade, y el síntoma sería "la página carga pero los cambios no llegan"
// — que es idéntico a otras dos causas distintas que ya están en el índice de fallas.
//
// NO se expone el backend del proyecto. El túnel apunta a un servidor de juguete escrito acá,
// que no tiene ni datos ni base: publicar el backend de desarrollo en internet para responder
// esta pregunta sería exponer usuarios y diagramas reales sin ninguna necesidad.

import http from "node:http";
import { spawn } from "node:child_process";
import { WebSocketServer, WebSocket } from "ws";

const PUERTO = 8123;
const MARCA = "modelcollab-tunel-" + Date.now();

let fallos = 0;
function comprobar(ok, texto, detalle = "") {
  console.log(`${ok ? "  OK  " : " FALLA"}  ${texto}${detalle ? "\n        " + detalle : ""}`);
  if (!ok) fallos++;
}

// ------------------------------------------------- el servidor de juguete

const servidor = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("pagina:" + MARCA);
});

const wss = new WebSocketServer({ server: servidor, path: "/ws" });
wss.on("connection", (socket) => {
  socket.on("message", (datos) => socket.send("eco:" + datos.toString()));
});

await new Promise((listo) => servidor.listen(PUERTO, listo));
console.log(`Servidor de juguete en http://localhost:${PUERTO} (sin datos del proyecto)\n`);

// ------------------------------------------------- el túnel

const tunel = spawn("./cloudflared.exe", [
  "tunnel", "--url", `http://localhost:${PUERTO}`, "--no-autoupdate",
], { cwd: process.cwd() });

const url = await new Promise((resolve, reject) => {
  let visto = "";
  const reloj = setTimeout(() => reject(new Error("el túnel no dio una URL en 60 s:\n" + visto)), 60000);
  const mirar = (buf) => {
    visto += buf.toString();
    const m = visto.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
    if (m) { clearTimeout(reloj); resolve(m[0]); }
  };
  tunel.stdout.on("data", mirar);
  tunel.stderr.on("data", mirar);
  tunel.on("exit", (c) => { clearTimeout(reloj); reject(new Error("cloudflared salió con " + c + "\n" + visto)); });
});

console.log(`Túnel arriba: ${url}\n`);
// El borde de Cloudflare tarda un instante en registrar la ruta.
await new Promise((r) => setTimeout(r, 4000));

// ------------------------------------------------- las comprobaciones

console.log("1. HTTPS con certificado válido");
try {
  const r = await fetch(url, { redirect: "follow" });
  const texto = await r.text();
  comprobar(r.status === 200, `responde 200`, `status ${r.status}`);
  comprobar(texto.includes(MARCA), "y lo que vuelve es lo que sirve MI servidor",
    texto.slice(0, 80));
  comprobar(url.startsWith("https://"),
    "la dirección es HTTPS y el certificado lo aceptó fetch sin excepciones");
} catch (e) {
  comprobar(false, "el GET por HTTPS falló", String(e));
}

console.log("\n2. La actualización a WebSocket a través del túnel");
const wsUrl = url.replace("https://", "wss://") + "/ws";
await new Promise((listo) => {
  const cliente = new WebSocket(wsUrl);
  const reloj = setTimeout(() => {
    comprobar(false, "el WebSocket no conectó en 25 s", wsUrl);
    try { cliente.terminate(); } catch {}
    listo();
  }, 25000);

  cliente.on("open", () => {
    comprobar(true, "el handshake se completó: el túnel SÍ negocia el upgrade", wsUrl);
    cliente.send("hola-" + MARCA);
  });
  cliente.on("message", (datos) => {
    const texto = datos.toString();
    comprobar(texto === "eco:hola-" + MARCA,
      "y los frames viajan en las dos direcciones", texto.slice(0, 60));
    clearTimeout(reloj);
    cliente.close();
    listo();
  });
  cliente.on("error", (e) => {
    comprobar(false, "el WebSocket dio error", String(e));
    clearTimeout(reloj);
    listo();
  });
});

console.log("\n3. La conexión se sostiene sin tráfico (lo que mata a una sala inactiva)");
await new Promise((listo) => {
  const cliente = new WebSocket(wsUrl);
  const desde = Date.now();
  const reloj = setTimeout(() => {
    // 70 s: pasa el minuto que es el tiempo de espera por defecto de casi todo intermediario.
    comprobar(cliente.readyState === WebSocket.OPEN,
      `sigue abierta despues de ${Math.round((Date.now() - desde) / 1000)} s sin mandar nada`);
    try { cliente.close(); } catch {}
    listo();
  }, 70000);
  cliente.on("error", (e) => {
    comprobar(false, "se cayó sola antes de los 70 s", String(e));
    clearTimeout(reloj);
    listo();
  });
  cliente.on("close", () => {
    if (Date.now() - desde < 68000) {
      comprobar(false, `la cerró el intermediario a los ${Math.round((Date.now() - desde) / 1000)} s`);
      clearTimeout(reloj);
      listo();
    }
  });
});

console.log(`\ncloudflared PID ${tunel.pid} — se baja ahora`);
tunel.kill();
servidor.close();
wss.close();

console.log(fallos === 0
  ? "\nTODO VERDE: el túnel sirve HTTPS y deja pasar WebSocket.\n"
  : `\n${fallos} comprobación(es) en rojo.\n`);
process.exit(fallos === 0 ? 0 : 1);
