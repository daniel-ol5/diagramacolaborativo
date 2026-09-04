# Spike B-2 — Veredicto: STT móvil offline

> # ✅ VEREDICTO: **el stack móvil es FLUTTER.**
>
> Transcripción correcta en español, en un teléfono Android real, con **modo
> avión activado** y `connectivity: none`. `speech_to_text` + el reconocedor
> nativo resuelven el STT offline sin compilaciones en la nube. **No se recurre
> a React Native ni a Vosk.** B-2 cerrado; el cronograma no se mueve.
>
> **Fecha:** 3 de septiembre de 2026 · **Track B (Móvil/IA)**

---

## 1. Qué decide este spike

Si la app móvil de la Fase 5 (18–20 sep) se escribe en **React Native** o en
**Flutter**. La decisión es irreversible en la práctica: tomarla el 18 de
septiembre no dejaría margen para rehacer nada.

**Criterio de aceptación (§B-2 del plan de ejecución):** un texto transcrito, en
pantalla, con **modo avión activado**, en un **teléfono real**, con captura de
pantalla guardada como evidencia.

---

## 2. Ruta probada y por qué en ese orden

El plan arquitectónico (§10.2) lista tres rutas:

| Ruta | Stack | Costo de llegar a evidencia |
| :--- | :--- | :--- |
| Principal | React Native + `@react-native-voice/voice` | **Alto** — exige Custom Dev Client vía EAS Build: cuenta Expo, compilación en la nube, 20–40 min por iteración |
| B (contingencia) | Flutter + `speech_to_text` | **Bajo** — Flutter ya instalado, `flutter run` directo al dispositivo |
| C (último recurso) | Vosk, modelo ES embebido (~45 MB) | Alto — suma 1 día al cronograma |

**Se ejecutó primero la Ruta B (Flutter).** Justificación: el criterio de
aceptación de B-2 no es *"qué framework es mejor"*, es *"¿hay texto transcrito en
modo avión?"*. Ambas rutas atacan **el mismo reconocedor nativo de Android**
(`android.speech.SpeechRecognizer`); la única diferencia real es cuánto cuesta
llegar a la evidencia. Con Flutter se llega en minutos, con RN en horas. Si
Flutter transcribe, el spike queda cerrado y el stack móvil queda definido; si
falla, recién ahí se paga el costo de EAS Build.

Decisión de orden tomada por la sesión `[planner]`, registrada aquí para la
defensa.

---

## 3. Entorno de la prueba

| Ítem | Valor |
| :--- | :--- |
| Flutter | 3.41.5 stable (revisión `2c9eb20739`) |
| Dart | 3.11.3 |
| Plugin STT | `speech_to_text` **7.4.0** |
| Plugin de red (evidencia visual) | `connectivity_plus` 7.3.1 |
| Proyecto | `spike-voz/` · applicationId `com.modelcollab.spike_voz` |
| compileSdk | 36 |
| Dispositivo | Xiaomi **M2012K11AG** (Mi 11T Pro) · serial `1cd53c50` |
| Android | **13** (API 33) · build `TKQ1.221114.001` |
| Proveedor de reconocimiento | `com.google.android.tts` v`17.52.22.ve.arm64` (Speech Services by Google) |
| Otros paquetes presentes | `com.google.android.as`, `com.google.android.as.oss` (Android System Intelligence) |

### Configuración Android relevante

`android/app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>

<queries>
    <intent>
        <action android:name="android.speech.RecognitionService"/>
    </intent>
    <package android:name="com.google.android.googlequicksearchbox"/>
</queries>
```

El bloque `<queries>` con `android.speech.RecognitionService` **no es opcional**
en Android 11+ (API 30). Sin él, el filtrado de visibilidad de paquetes impide
que la app resuelva el servicio de reconocimiento y `initialize()` devuelve
`false` sin ningún error descriptivo. Es la causa nº 1 de falsos negativos en
este tipo de spike.

### Llamada de escucha

```dart
await _speech.listen(
  onResult: ...,
  listenOptions: SpeechListenOptions(
    onDevice: true,                    // fuerza reconocimiento local
    partialResults: true,
    listenMode: ListenMode.dictation,
    localeId: 'es_...',                // elegido de speech.locales()
    listenFor: Duration(seconds: 30),
    pauseFor: Duration(seconds: 4),
  ),
);
```

`onDevice: true` es la clave del spike: obliga al reconocedor a resolver en el
dispositivo y a **fallar** si no puede, en lugar de caer silenciosamente a la
nube. La app expone un switch para apagarlo y así distinguir *"no hay modelo
local"* de *"no reconoce nada"*.

---

## 4. Resultados

### 4.1. Corrida decisiva — modo avión

| Medición | Resultado |
| :--- | :--- |
| `initialize()` | **`true`** |
| Locales `es_*` reportados | `es_ES`, `es_US` |
| `localeId` usado | **`es_US`** |
| `onDevice` pedido | `false` (ver §5.4 — forzarlo es contraproducente en este equipo) |
| **Modo avión activo** | **SÍ** — `settings get global airplane_mode_on` → `1`, verificado por `adb` en el momento de la captura; la app muestra `connectivity: none` |
| Frase dictada | `agenda una cita para mañana a las diez` |
| **Texto exacto devuelto** | **`agenda una cita para mañana a las 10`** |
| Latencia | **13 682 ms** (medida desde el toque del botón, incluye el habla) |
| Errores | ninguno |

Registro textual del resultado, emitido por la propia app a `logcat`:

```
09-03 21:44:07.758 I flutter : SPIKE_FINAL|avion=true|onDevice=false
                               |locale=es_US|ms=13682
                               |texto=agenda una cita para mañana a las 10
```

**Evidencia:** `evidencias/spike-voz-modo-avion.png` — banner verde
*"SIN RED — MODO AVIÓN OK"*, `connectivity: none`, ícono de avión en la barra de
estado del sistema y el texto transcrito en pantalla. Entorno del dispositivo en
`evidencias/spike-voz-entorno.txt` (incluye `modo avion: 1`).

### 4.2. Corrida de contraste — con Wi-Fi

| Medición | Resultado |
| :--- | :--- |
| Texto devuelto | `agenda una cita para mañana a las 10` |
| Latencia | 4 498 ms |
| `onDevice` pedido | `false` |

**Evidencia:** `evidencias/spike-voz-baseline-con-red.png`

Se corrió **antes** de la decisiva, a propósito: prueba que micrófono, permisos y
reconocedor funcionaban, de modo que cualquier fallo posterior fuera atribuible a
la falta de red y no al entorno. El banner rojo *"HAY RED — EL SPIKE NO VALE"*
está en la captura para que nadie la confunda con la evidencia decisiva.

### 4.3. Lectura de los números

El offline tarda **~3× más** que la nube (13,7 s vs 4,5 s), aunque la
comparación no es limpia: la latencia se mide desde el toque del botón e incluye
el tiempo de habla y la espera de `pauseFor` (4 s). El margen real de
procesamiento offline es holgado para un asistente de comandos. **La precisión
fue idéntica** — texto correcto en ambos casos.

---

## 5. Lo que NO funcionó

_Para la defensa vale más un spike con fallos documentados que uno que "salió
perfecto". Se registra todo, incluidos los tropiezos de entorno._

### 5.1. `permission_handler` 13.0.1 — incompatible, eliminado

Se agregó inicialmente para pedir RECORD_AUDIO de forma explícita. La build de
Android falló al compilar el script Gradle del plugin:

```
e: permission_handler_android-14.0.0/android/build.gradle.kts:68:1:
   Unresolved reference (kotlin { compilerOptions { jvmTarget ... } })
```

`permission_handler_android` 14.0.0 usa la DSL `kotlin { compilerOptions { } }`,
que requiere una versión del Kotlin Gradle Plugin más nueva que la que trae el
template de Flutter 3.41.5.

**Resolución:** se eliminó la dependencia. Es innecesaria —
`SpeechToText.initialize()` ya solicita el permiso de micrófono por su cuenta.
Una dependencia menos en el camino crítico.

### 5.2. Dispositivo en estado `unauthorized`

`adb devices` detecta el teléfono (`1cd53c50`) pero como `unauthorized`: falta
aceptar la huella RSA en la pantalla del celular. Bloqueo de hardware, no de
código. Checklist para resolverlo: `spike-voz/CHECKLIST_DANIEL.md`.

### 5.3. MIUI bloquea la instalación y la automatización del dispositivo

Tres fricciones distintas del ROM de Xiaomi, todas ajenas al spike pero que
consumieron tiempo real:

1. `adb install` falla con `INSTALL_FAILED_USER_RESTRICTED`. MIUI exige activar
   **"Instalar vía USB"** en Opciones de desarrollador **y además** muestra un
   diálogo en el teléfono con cuenta regresiva que **se auto-rechaza a los ~10
   segundos**. Por eso el error dice literalmente *"Install canceled by user"*
   sin que el usuario haya cancelado nada. Empujar el APK a `/data/local/tmp` y
   usar `pm install` da exactamente el mismo rechazo: no es un rodeo válido.
2. `adb shell input tap` falla con
   `SecurityException: Injecting input events requires ... INJECT_EVENTS`.
   No se puede automatizar la operación de la UI: **cada dictado exige que una
   persona toque el teléfono**.
3. `am start` sobre la pantalla de descarga de idiomas offline
   (`...languagepack.settings.AddLanguagesActivity`) falla con
   `Permission Denial: not exported`. La descarga del modelo hay que hacerla a
   mano por la UI.

**Consecuencia para la Fase 5:** cualquier plan de pruebas automatizadas de la
app de voz sobre dispositivos Xiaomi tiene que asumir intervención manual, o
usar un dispositivo con ROM menos restrictivo.

### 5.4. `onDevice: true` → `error_language_unavailable` (modelo local ausente)

Es el hallazgo tecnico central del spike.

| Corrida | Red | `onDevice` | Resultado |
| :--- | :--- | :--- | :--- |
| Contraste | Wi-Fi | `false` | ✅ `agenda una cita para mañana a las 10` — 4498 ms |
| Forzada local | Wi-Fi | `true` | ❌ `error_language_unavailable` (**permanente: true**), texto vacío |

La segunda corrida falló **con red disponible**. Eso descarta la red como causa
y aísla el diagnóstico: el reconocedor resuelve el idioma `es_ES` en
`locales()`, pero **no tiene el modelo acústico descargado en el dispositivo**.

Este es exactamente el motivo por el que el switch de `onDevice` se incluyó en
la app. Sin él, las dos causas —"no hay modelo local" y "no hay red"— producen
la misma pantalla vacía y son indistinguibles. Con él, quedan separadas antes
de siquiera activar el modo avión.

> **Que `locales()` liste `es_ES` no prueba que el español offline esté
> instalado.** El reconocedor conoce el idioma de todas formas. La única prueba
> es forzar `onDevice: true` y ver si responde. Éste es el falso positivo más
> peligroso de este spike, y quedó documentado porque se cayó en él.

También se observó `error_busy` al tocar el botón repetidamente mientras una
sesión anterior seguía cerrándose: el reconocedor nativo no acepta sesiones
solapadas. Una app real necesita bloquear el botón hasta recibir `done`.

### 5.5. El reconocedor normaliza numerales a dígitos

Se dictó *"a las **diez**"* y devolvió *"a las **10**"*. Insumo directo para el
`SlotExtractor` de la Fase 5 (§10.3): el parser de `DATETIME` **no puede asumir
que los números llegan como palabras** — tiene que aceptar ambas formas. Es un
hallazgo lateral del spike, fuera de su alcance, pero que ahorra una corrección
el día 19.


---

### 5.6. `onDevice: true` apunta al stack de reconocimiento equivocado

Ampliación de §5.4, con la causa raíz ya identificada en el código del plugin.

`SpeechToTextPlugin.kt:623-626` — cuando `onDevice = true` y la API ≥ 31, el
plugin **no** usa el `SpeechRecognizer` normal:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && onDevice) {
    speechRecognizer = createOnDeviceSpeechRecognizer(pluginContext!!)
```

`createOnDeviceSpeechRecognizer()` resuelve contra **Android System
Intelligence** (`com.google.android.as`), que mantiene **su propio juego de
modelos**. Pero el paquete de español descargado en el dispositivo —
**Español (EE.UU.), 74 MB, v3056** — pertenece a **Speech Services by Google**
(`com.google.android.tts`), que es además el `voice_recognition_service`
configurado en el sistema.

**Son dos stacks de reconocimiento on-device distintos, y el modelo estaba en el
que esa API no consulta.** De ahí el `Error 13 / ERROR_LANGUAGE_UNAVAILABLE`
disparado en 82-337 ms, tanto con `es_US` como con `es_ES`.

**Conclusión operativa, y es contraintuitiva:** para lograr STT offline en este
equipo hay que **dejar `onDevice` en `false`** y quitar la red. Sin red el
reconocedor normal no tiene a dónde ir salvo al modelo local, y funciona.
Forzar `onDevice: true` — que es lo que uno haría por instinto para "asegurar"
el offline — **rompe** el caso que pretende garantizar.

### 5.7. `locales()` lista locales sin modelo descargado

`locales()` devolvía `es_ES` y `es_US`. La primera versión de la app elegía
`es_ES` por cercanía dialectal, y fallaba. El único modelo descargado era
`es_US`.

**Que un locale aparezca en `locales()` no dice nada sobre si su modelo offline
está instalado.** El orden de preferencia debe basarse en qué modelo está
efectivamente descargado, no en el dialecto. Por eso la app terminó con un
selector de locale en pantalla: la corazonada no es un método.

### 5.8. El resultado FINAL llega vacío; el texto está en los PARCIALES

Con reconocimiento offline, la secuencia observada fue: varios resultados
parciales **con** el texto y, al cerrar la sesión, un `finalResult` con
`recognizedWords` **vacío**.

```
SPIKE_FINAL|avion=true|onDevice=false|locale=es_US|ms=9731|texto=
```

La app hacía `_texto = r.recognizedWords` en cada callback, de modo que el
resultado final vacío **borraba la transcripción** justo al terminar de hablar:
el texto aparecía en pantalla y se desvanecía solo. Corregido reteniendo el
último resultado no vacío.

> **Impacto directo en la Fase 5:** un `IntentClassifier` que escuche únicamente
> `finalResult` — la implementación por defecto y la que muestran casi todos los
> ejemplos — **no recibiría nada** en modo offline. Debe consumir el último
> parcial no vacío.

---

## 6. Alcance — lo que este spike deliberadamente NO hace

No hay `IntentClassifier`, no hay `intents.json`, no hay app móvil real. El
metamodelo de intenciones (§10.3 del plan arquitectónico) depende del generador
Spring Boot y corresponde a la Fase 5. Este spike responde **una sola pregunta**
y nada más.

---

## 7. Cómo reproducirlo

```bash
cd spike-voz
flutter pub get
flutter run          # con el teléfono conectado y autorizado
```

Captura de evidencia:

```bash
adb exec-out screencap -p > evidencias/spike-voz-modo-avion.png
```

(`adb` no está en el PATH: `C:\Users\Daniel\AppData\Local\Android\Sdk\platform-tools\adb.exe`)

`spike-voz/evidencia.sh` automatiza la captura: solo escribe
`spike-voz-modo-avion.png` si `airplane_mode_on` es `1`; con red guarda el
archivo como baseline. Una corrida de contraste no puede colarse como evidencia
decisiva.

Preparación del teléfono paso a paso: `spike-voz/CHECKLIST_DANIEL.md`.

---

## 8. Qué se lleva la Fase 5 de este spike

1. **Stack móvil: Flutter.** Decidido y cerrado.
2. **No forzar `onDevice: true`.** Apunta al stack de modelos equivocado (§5.6).
3. **Leer el último parcial no vacío**, nunca solo `finalResult` (§5.8).
4. **Elegir el locale por modelo descargado**, no por dialecto (§5.7).
5. **El parser de `DATETIME` debe aceptar dígitos y palabras**: se dictó
   *"a las diez"* y volvió *"a las 10"* (§5.5).
6. **Bloquear el botón de micrófono hasta `done`**: las sesiones solapadas dan
   `error_busy` (§5.4).
7. **El paquete de idioma offline es un requisito de instalación**, no un
   detalle: sin él no hay asistente sin conexión, y el fallo es silencioso. La
   app debe detectarlo y guiar al usuario.
