# Checklist para Daniel — Spike de voz (5 minutos)

Tu celular ya está conectado por USB y lo detecto. Falta que hagas 4 cosas.
La **número 2 es la que hace o rompe la prueba**, no la saltees.

---

## 1) Autorizar la depuración USB

Ahora mismo el celular aparece como **"unauthorized"**: está enchufado pero no me
deja instalar nada.

- Mirá la pantalla del celular. Debería haber un cartel que dice
  **"¿Permitir depuración USB?"** con un código largo.
- Marcá **"Siempre permitir desde esta computadora"** y tocá **Permitir / OK**.
- Si no aparece el cartel: desenchufá y volvé a enchufar el cable.
- Si tampoco aparece: falta activar **Opciones de desarrollador**
  (Ajustes → Acerca del teléfono → tocar **7 veces** en "Número de compilación"),
  y después Ajustes → Sistema → Opciones de desarrollador → activar
  **Depuración por USB**.

Avisame cuando lo hayas hecho y yo verifico.

---

## 2) Descargar el reconocimiento de voz en ESPAÑOL para uso sin conexión

**Este es el paso crítico.** Sin el paquete de idioma descargado, la prueba falla
por una razón que no tiene nada que ver con lo que queremos medir.

Ruta (varía un poco según la marca del celular):

- **Ajustes → Google → Configuración de apps de Google → Búsqueda, Asistente y Voz
  → Voz → Reconocimiento de voz sin conexión → pestaña "Todos" → Español →
  Descargar**

Rutas alternativas si no encontrás esa:
- Ajustes → Sistema → Idiomas e introducción → Salida de texto a voz / Voz
- Ajustes → buscar la palabra **"sin conexión"** o **"offline"** en el buscador
  de Ajustes

Cuando termine, en la lista **Español** tiene que decir "Descargado" / "Instalado"
(no un botón de descarga). Si tu celular ofrece "Español (Estados Unidos)",
"Español (España)" o "Español (México)", descargá cualquiera — con uno alcanza,
pero si podés bajá el que coincida con tu forma de hablar.

---

## 3) Activar MODO AVIÓN

Bajá la barra de notificaciones y activá el **modo avión** (el ícono del avión).

- Verificá que **Wi-Fi y datos móviles queden apagados**. En algunos celulares el
  Wi-Fi se vuelve a encender solo después del modo avión — si pasa eso, apagalo
  a mano.
- La app te lo muestra en pantalla: la barra de arriba tiene que estar **VERDE**
  y decir *"SIN RED — MODO AVIÓN OK"*. Si está **ROJA**, todavía hay red y la
  prueba no vale.

> El cable USB puede quedar enchufado: USB no es red, no invalida nada.

---

## 4) Dictar la frase

En la app (se llama **spike_voz**):

1. Aceptá el permiso del **micrófono** cuando lo pida.
2. Tocá el botón grande **"TOCAR Y DICTAR"**.
3. Decí, clarito y a velocidad normal:

   > **"agenda una cita para mañana a las diez"**

4. Esperá a que el texto aparezca en el recuadro grande y avisame.

**No borres nada de la pantalla** — necesito sacar la captura tal cual está,
porque esa captura es la evidencia para la defensa.

---

## Si sale mal

Que salga mal **también es un resultado válido** y lo documento igual. No toques
nada, solo avisame qué viste:

- El recuadro quedó **vacío** después de dictar.
- Apareció un **ERROR** en el panel de arriba (`error_network`,
  `error_no_match`, etc.).
- En "locales es" dice **"NINGUNO (falta el paquete de español)"** → volvé al
  paso 2, el paquete no quedó instalado.

Si con `Forzar onDevice: true` falla, apagá ese switch y probá de nuevo: eso me
dice si el reconocedor estaba cayendo a la nube, que es exactamente el dato que
el spike tiene que responder.
