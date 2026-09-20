// Deja todo listo para grabar CU16 y CU17 en UNA sola corrida limpia.
//
// POR QUE EXISTE: la cuota de Gemini es el recurso escaso. Cada intento fallido de grabación
// gasta lo que después hace falta en vivo el 23. Así que todo lo que NO necesita el modelo
// —crear la cuenta, el proyecto, el diagrama, las clases sobre las que se va a dictar— se hace
// acá antes de apretar grabar, y la grabación queda reducida a lo único que hay que demostrar.
//
// Y la cuenta es NUEVA a propósito. Las cuentas sembradas tienen 66 proyectos de prueba y, peor,
// sus contraseñas están publicadas en el repositorio: proyectar esa sesión es proyectar una
// credencial pública.
//
// Uso:  node preparar-grabacion.mjs
//       BASE=http://localhost:8099 node preparar-grabacion.mjs   (contra el sustituto)

const BASE = process.env.BASE ?? "http://localhost:8080";
const WEB = process.env.WEB ?? "http://localhost:5173";
const sello = new Date().toISOString().slice(0, 10).replace(/-/g, "");

const CORREO = `demo-${sello}-${Date.now().toString().slice(-4)}@modelcollab.demo`;
const CLAVE = "Demo" + Date.now().toString().slice(-6) + "!";

async function api(ruta, opciones = {}) {
  const r = await fetch(BASE + ruta, opciones);
  const texto = await r.text();
  let cuerpo = null;
  try { cuerpo = texto ? JSON.parse(texto) : null; } catch { cuerpo = texto; }
  if (r.status >= 300) throw new Error(`${ruta} -> ${r.status} ${texto.slice(0, 160)}`);
  return cuerpo;
}

const sesion = await api("/api/v1/auth/register", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: CORREO, password: CLAVE, fullName: "Daniel Olmos" }),
});
const auth = { Authorization: `Bearer ${sesion.token}`, "Content-Type": "application/json" };

const proyecto = await api("/api/v1/projects", {
  method: "POST", headers: auth,
  body: JSON.stringify({ name: "Colegio", description: "Gestión académica" }),
});
const diagrama = await api(`/api/v1/projects/${proyecto.id}/diagrams`, {
  method: "POST", headers: auth, body: JSON.stringify({ name: "Académico" }),
});

// Dos clases sobre las que dictar. Se crean por el camino de reconciliación offline, que es el
// único que acepta un lote por REST — no hace falta abrir un WebSocket para esto.
const id = () => crypto.randomUUID();
const clase = (nombre, x, y, atributos) => ({
  id: id(), name: nombre, visibility: "PUBLIC", isAbstract: false,
  position: { x, y }, width: 240, height: 180, attributes: atributos, methods: [],
});
const atributo = (nombre, tipo, extra = {}) => ({
  id: id(), name: nombre, type: tipo, visibility: "PRIVATE",
  isPrimaryKey: false, isNullable: true, isUnique: false, ...extra,
});

const alumno = clase("Alumno", 120, 120, [
  atributo("id", "UUID", { isPrimaryKey: true, isNullable: false }),
  atributo("nombre", "VARCHAR", { length: 120, isNullable: false }),
]);
const curso = clase("Curso", 520, 120, [
  atributo("id", "UUID", { isPrimaryKey: true, isNullable: false }),
  atributo("nombre", "VARCHAR", { length: 100, isNullable: false }),
]);

await api(`/api/v1/diagrams/${diagrama.id}/sync-offline`, {
  method: "POST", headers: auth,
  body: JSON.stringify({
    operations: [alumno, curso].map((c) => ({
      op: "ADD_CLASS", targetId: null, clientMutationId: id(), payload: c,
      clientTimestamp: Date.now(),
    })),
  }),
});

const foto = await api(`/api/v1/diagrams/${diagrama.id}/snapshot`, { headers: auth });
const clases = foto.currentState.classes.map((c) => c.name);

console.log(`
====================================================================
  LISTO PARA GRABAR
====================================================================

  Entrar en:   ${WEB}
  Correo:      ${CORREO}
  Clave:       ${CLAVE}

  Diagrama:    ${WEB}/diagrams/${diagrama.id}
  Clases ya puestas: ${clases.join(", ")}

--------------------------------------------------------------------
  LA SECUENCIA, en este orden y sin repetir nada
--------------------------------------------------------------------

  1. Mostrar el diagrama con Alumno y Curso.

  2. DICTAR:  "relaciona Alumno con Curso de muchos a muchos"
     Esperar a que aparezca la línea. Que se vea el texto que devuelve
     el asistente diciendo qué entendió.

  3. DICTAR:  "agrega el atributo legajo varchar a Alumno"

  4. QUE SE VEA EL AVISO DE SIMULADO APAGADO.
     Es lo único que distingue esta grabación de una corrida con el
     sustituto. Sin eso, la grabación no demuestra lo que queremos.

  5. LA FOTO (CU17): subir una pizarra que tenga
       - clases que NO estén ya en el diagrama, o van a entrar
         duplicadas con "(2)" y parece un error;
       - UNA CLASE ASOCIACIÓN: una caja colgando de la línea con
         trazo punteado. Es el único punto que queda sin verificar.
     Revisar la propuesta en el modal y confirmar.

--------------------------------------------------------------------
  ANTES DE APRETAR GRABAR
--------------------------------------------------------------------

  * Ensayar la secuencia entera contra el SUSTITUTO (un backend sin
    GEMINI_API_KEY). No gasta una sola llamada.

    OJO CON ESTO, que me pasó al probarlo: las frases de arriba están
    elegidas para que las entiendan LOS DOS. El sustituto interpreta por
    expresiones regulares, así que es mucho más estricto que el modelo
    real — "relaciona Alumno con Curso muchos a muchos" (sin el "de") le
    devuelve "no supe traducir eso", y "agregale a Alumno un atributo
    legajo de tipo VARCHAR" también.

    O sea que un ensayo con OTRAS palabras puede dar "no entendí" y
    hacernos creer que algo está roto cuando no lo está. Si se cambian
    las frases, hay que probarlas primero contra el sustituto: si ahí
    pasan, el modelo real —que es más flexible— también.
  * Recién cuando salga fluida, cambiar al modelo real y grabar de una.
  * Si un comando falla por cuota, PARAR. Reintentar hunde el cupo que
    hace falta el 23.

====================================================================
`);
