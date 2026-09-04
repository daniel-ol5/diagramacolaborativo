# Spike B-2 — Veredicto: STT móvil offline

> **VEREDICTO:** ⏳ _pendiente de ejecución en teléfono físico_
> **Estado:** app compilada y lista; esperando que el dispositivo sea autorizado y
> que el paquete de español offline esté descargado.
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

| Medición | Resultado |
| :--- | :--- |
| `initialize()` | _(pendiente)_ |
| Locales `es_*` reportados por el dispositivo | _(pendiente)_ |
| `localeId` usado | _(pendiente)_ |
| Reconocedor reporta soporte on-device | _(pendiente)_ |
| Modo avión activo durante la prueba | _(pendiente)_ |
| Frase dictada | `agenda una cita para mañana a las diez` |
| **Texto exacto devuelto** | _(pendiente)_ |
| Latencia aproximada | _(pendiente)_ |
| Errores observados | _(pendiente)_ |

**Evidencia:** `evidencias/spike-voz-modo-avion.png` _(pendiente)_

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

Es el hallazgo central del spike hasta el momento.

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
