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
