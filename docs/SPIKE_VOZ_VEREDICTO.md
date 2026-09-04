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
| Dispositivo | _(pendiente — serial `1cd53c50`)_ |
| Modelo / versión Android | _(pendiente)_ |

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

### 5.3. _(reservado para los hallazgos de la ejecución en el teléfono)_

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
