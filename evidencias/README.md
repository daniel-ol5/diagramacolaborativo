# Evidencias

**Esto no es documentación: es prueba.** Cada archivo de esta carpeta respalda una
afirmación concreta sobre el sistema, y está versionado junto al código que lo produjo para
que se pueda fechar contra el commit que lo generó.

Una captura suelta en un escritorio no prueba nada — nadie sabe cuándo se sacó ni contra qué
versión. Ésa es toda la razón por la que esta carpeta está en el repositorio.

---

## Spike de voz sin conexión — 3 de septiembre de 2026

| Archivo | Qué prueba |
| :--- | :--- |
| `spike-voz-modo-avion.png` | **La prueba decisiva.** Transcripción correcta con el **modo avión activado**, en un teléfono real |
| `spike-voz-baseline-con-red.png` | Corrida de contraste **con red**, para tener con qué comparar |
| `spike-voz-entorno.txt` | Volcado del dispositivo: Xiaomi M2012K11AG, Android 13 (API 33), motores de reconocimiento instalados |

### Qué pregunta respondían

El 3 de septiembre había que decidir el stack móvil, y había una pregunta que podía invalidar
tres semanas de plan:

> **¿Un teléfono Android transcribe voz sin internet?**

- Si **sí** → la app móvil se construye con Flutter y el cronograma no cambia.
- Si **no** → hay que embeber un motor de voz de ~45 MB, sumar un día al cronograma y
  recortar alcance en otra fase.

Descubrirlo el 18 de septiembre habría sido fatal. Así que se escribió **la aplicación más
pequeña posible capaz de responder esa única pregunta** — un botón, un micrófono y un cuadro
de texto — se instaló en un teléfono real, se activó el modo avión y se dictó una frase.

**Respondió: sí.** Transcripción correcta, sin red, en 13 682 ms.

### Dónde está esa aplicación

**Se eliminó del repositorio el 13 de septiembre.** Era un experimento con una sola pregunta
que hacer, y la hizo. No es la app móvil del proyecto —ésa se **genera** desde las plantillas
de `backend/src/main/resources/templates/mobile/`— no se despliega y no evoluciona.

Lo que queda es lo único que importaba: **estas tres evidencias**.

### El fallo que casi produce una evidencia falsa

Vale registrarlo porque es material de defensa. El script que recolectaba la evidencia
escribía el archivo `spike-voz-modo-avion.png` **sin comprobar que el modo avión estuviera
activado**, y una corrida de pre-chequeo llegó a generar un archivo con ese nombre teniendo
el Wi-Fi encendido.

Delante de un tribunal eso no es un descuido: es una captura que afirma algo que no ocurrió.

Se corrigió para que ese nombre se use **sólo** si `adb shell settings get global
airplane_mode_on` devuelve `1`; en caso contrario guarda como *baseline*. Una corrida de
contraste ya no puede colarse como evidencia decisiva.

---

## Interoperabilidad XMI 2.1 — 13 de septiembre de 2026

| Archivo | Qué prueba |
| :--- | :--- |
| `inventario.xmi` | Exportación del diagrama de inventario: **dos paquetes** y conectores con waypoints |
| `barberia.xmi` | Exportación del diagrama de barbería |

Generados **por el endpoint HTTP real** con la aplicación levantada, no por un test unitario.

El diagrama de inventario es el caso difícil a propósito: tiene dos paquetes y conectores que
el usuario acomodó a mano. Una implementación ingenua pierde las dos cosas.

Cada archivo lleva **dos secciones de extensión**: la propia —que garantiza el viaje de ida y
vuelta sin pérdida entre dos instancias de ModelCollab— y otra en el formato que Enterprise
Architect entiende, para que la herramienta pueda abrir la lámina y no sólo listar los
objetos.

---

## Lienzo web

| Carpeta | Qué prueba |
| :--- | :--- |
| `dia06-web/` | Los cuatro diagramas renderizados con la notación UML completa: rombos, triángulos, líneas punteadas y multiplicidades |
| `dia11-crear-editar/` | Dos navegadores independientes: uno crea una clase de un dominio nuevo y el otro la ve aparecer **en vivo, sin recargar** |
| `tokens-contraste/` | Antes/después de reemplazar los colores sueltos por `web/src/styles/tokens.css` |

---

## Tokens de diseño y contraste WCAG AA — 15 de septiembre de 2026

| Archivo | Qué prueba |
| :--- | :--- |
| `tokens-contraste/canvas-antes.png` / `canvas-despues.png` | El lienzo de barbería antes y después: los conectores pasan de un gris casi invisible a un trazo de 1,5px con contraste real |
| `tokens-contraste/login-antes.png` / `login-despues.png` | La pantalla de acceso: bordes de los campos y el link de "Contraseña" antes y después |

**La evidencia primaria es `scripts/check-contrast.mjs`, no las capturas** — misma regla que
el resto de esta carpeta. Corre la fórmula de contraste relativo de WCAG 2.x contra los
valores reales de `tokens.css` (no contra números copiados a mano) y falla si alguno queda
por debajo del piso:

```
node scripts/check-contrast.mjs
```

Los colores sueltos que reemplazó (14 checks, todos verificados, 1 corregido en el camino):

| Antes | Contraste | Token | Contraste |
| :--- | :--- | :--- | :--- |
| `#ccc` / `#ddd` / `#eee` (bordes) | 1.16:1 – 1.61:1 | `--color-border` | 4.49:1 |
| `#999` / `#888` (texto) | 2.85:1 / 3.54:1 | `--color-text-tertiary` | 5.33:1 |
| `#357cda` (link) | 4.16:1 | `--color-link` | 6.70:1 |
| `#e8590c` + texto blanco | 3.58:1 | `--color-warning` | 6.25:1 |
| `#9aa5d8` + texto blanco (botón deshabilitado) | 2.40:1 | `--color-primary-muted` | 4.98:1 |
| `#b1b1b7` trazo de conector (default de React Flow) | 2.13:1 | `--canvas-edge-stroke` | 6.61:1 |
| `#2b8a3e` (éxito/"conectado") | 4.37:1 — no llegaba a 4.5 | `--color-success` | 5.38:1 |

El último de la tabla no estaba en la lista original de defectos reportados — lo encontró el
propio script al medir cada token, no una revisión manual. La regla de esta carpeta cumplida
dos veces en la misma tarea: primero para el trazo del conector, después para un color que
nadie había señalado todavía.

El trazo del conector lleva además un chequeo de que el estado **seleccionado siga
distinguiéndose** del de reposo después de subir el contraste de los dos — con `deltaE`
(CIE76), el mismo criterio usado para la paleta de colaboradores, no con razón de contraste:
dos colores pueden tener luminancia parecida y ser igual de distinguibles si difieren en tono,
que es exactamente el caso de un gris de reposo contra un azul de marca seleccionado.

Alcance deliberadamente acotado a esta pasada: **no** incluye el tamaño de fuente de
atributos/métodos dentro de la caja de clase (quedó en 11px, no en el piso de 13px sugerido
para el lienzo) porque la caja tiene una altura fija que viene del dato del diagrama, no del
CSS — subir el tamaño ahí recorta filas enteras sin avisar (verificado, revertido). Se resuelve
cuando la caja deje de tener una altura heredada del Día 6, junto con las pantallas.

---

## Paleta "para ingenieros" — 15 de septiembre de 2026

Segunda pasada sobre `tokens.css`: la primera (arriba) sólo corregía contraste sobre los
colores que ya había; ésta rediseña la paleta con el criterio de las herramientas que un
ingeniero elige — Linear, Figma, VS Code, Excalidraw, draw.io — sin bajar ningún número por
debajo de lo que `scripts/check-contrast.mjs` exige (18 checks, incluidos los de esta paleta).

| Archivo | Qué prueba |
| :--- | :--- |
| `paleta-ingenieros/canvas.png` | El lienzo con la paleta nueva: comparar contra `tokens-contraste/canvas-despues.png` (la pasada anterior) |
| `paleta-ingenieros/login.png` | La pantalla de acceso: comparar contra `tokens-contraste/login-despues.png` |
| `paleta-ingenieros/lienzo-reticula-zoom.png` | Acercamiento a una zona vacía del lienzo — la retícula de puntos que antes no existía |

Seis cambios, cada uno con un motivo, no un gusto:

1. **Rampa neutra fría (pizarra), no grises puros ni blanco plano.** La barra de herramientas y
   las pantallas standalone pasan de blanco liso a un gris azulado sutil (`--gray-page`,
   `--gray-chrome`) — la interfaz se corre del medio.
2. **Un solo acento**, `#365d94` (antes `#3b5bdb`, más saturado), usado sólo para la acción
   principal y la selección — el link ahora es literalmente `var(--color-primary)`, no un azul
   aparte.
3. **El lienzo es un plano distinto** (`--canvas-bg: #fbfcfd`) del resto de la interfaz, con una
   retícula de puntos sutil (`<Background color="var(--canvas-grid-dot)" gap={20} size={1.5}/>`
   de React Flow) — la señal universal de "esto es un espacio de trabajo".
4. **Monoespaciada para identificadores**: nombre/tipo de atributo, firma de método,
   multiplicidad de relación (`--font-mono`, sólo fuentes de sistema — sin dependencia externa,
   coherente con una app que tiene que poder funcionar sin red).
5. **Bordes finos en vez de sombras difusas**: la caja de clase, los modales y la tarjeta de
   acceso bajan de una sombra de 16px de difusión a un borde de 1px con un resto mínimo de
   elevación.
6. **Densidad**: el interlineado y el padding de las filas de atributos/métodos se ajustaron
   para mostrar más contenido antes que más aire.

Dos anclas que **no se tocaron** a propósito: `--color-border-strong` (`#1a1a2e`, el borde de
clase UML) y el texto del lienzo. La paleta de colores de colaborador que asigna el servidor
está medida contra esos dos valores exactos — cambiarlos sin avisar habría invalidado esa
medición.

---

## La regla que ordena esta carpeta

**La evidencia primaria es la aserción automática; la captura ilustra.**

Salió de un episodio concreto: unas capturas del lienzo salían sin las líneas, y el primer
diagnóstico —*"es un defecto del navegador al capturar"*— resultó **falso**. Era un error real
de la aplicación.

Lo que lo destapó no fue mirar más capturas, sino **verificar por otro camino**: un script que
interroga la página por dentro y **falla** si falta un marcador. Desde entonces, cuando existe
una verificación automática, ésa es la prueba y la imagen es lo que se muestra en pantalla.

La distinción importa: *"reintenté la captura hasta que salió bien"* y *"elegí la evidencia
que me convenía"* se parecen demasiado desde afuera. Con un verificador determinista que corre
y falla, la respuesta a *"¿cómo sé que no elegiste la captura favorable?"* deja de ser
*"porque la miré"*.
