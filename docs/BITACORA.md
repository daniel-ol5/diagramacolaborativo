# Bitácora de ejecución — ModelCollab

Registro diario de lo que se hizo, lo que se decidió y lo que se desvió del plan.
Se actualiza al cierre de cada día. Es el insumo honesto para la defensa.

---

## Enmiendas al plan arquitectónico (Fase 0)

`PLAN_ARQUITECTONICO_FASE_0.txt` es documento entregado. Se modifica sólo con
autorización explícita de Daniel y **toda enmienda queda registrada acá**, con fecha,
motivo y el texto exacto antes y después. Una edición silenciosa sobre un documento
de 83 KB es indetectable, y eso es justamente lo que esta sección impide.

### E-01 · 6 de septiembre de 2026 — §7, `classes[].visibility` recibe `"default": "PUBLIC"`

**Autorizada por Daniel el 6/9/2026.**

| | |
| :--- | :--- |
| **Antes** | `"visibility": { "type": "string", "enum": ["PUBLIC", "PACKAGE", "PROTECTED", "PRIVATE"] }` |
| **Después** | `"visibility": { "type": "string", "enum": [...], "default": "PUBLIC" }` |

**Motivo.** El campo estaba declarado opcional y sin valor por defecto. La
implementación Java lo normalizaba a `PUBLIC` en el constructor compacto de
`ClassEntity`; la implementación TypeScript del lienzo lo dejaba ausente. Dos capas
interpretando distinto el mismo dato faltante, sin que ninguna fallara ni avisara.
Detectado el 6/9 al escribir los tipos del frontend contra §7 (ver D-16, guía del
día 04).

**Alcance.** Un solo campo. **No se tocó** `attributes[].visibility` ni
`methods[].visibility`: esas dos siguen en `required` y sin default, y la asimetría es
deliberada — son portantes para el generador (deciden si un campo sale `private` o un
método `public`), mientras que la visibilidad de la clase no lo es, porque una entidad
JPA generada es `public` siempre.

**Verificación.** El bloque JSON de §7 se volvió a parsear completo después del cambio:
válido, y el resto del schema intacto.

---

## Día 1 — Miércoles 3 de septiembre

**Objetivo del día (según `PLAN_EJECUCION.md`):** B-1 modelo canónico congelado + B-2 spike de voz.

### Auditoría del entorno de desarrollo

| Herramienta | Estado | Consecuencia |
| :--- | :---: | :--- |
| Java 21.0.6 LTS | ✅ | Ninguna |
| Node 22.19 / npm 10.9 | ✅ | Ninguna |
| Flutter 3.41.5 + Dart 3.11.3 | ✅ | Habilita la Ruta B del spike sin build en la nube |
| Android SDK + adb | ✅ | Fuera del PATH; usar ruta absoluta |
| PostgreSQL 18 (servicio corriendo) | ✅ | El plan especifica PG16; en local se trabaja sobre 18, el `docker-compose` fija 16 |
| Maven | ❌ | Se usa el Maven Wrapper (`mvnw`) del proyecto generado |
| Docker / Docker Compose | ❌ | **Afecta el criterio de aceptación de B-3** |

### Desviaciones respecto al plan

**D-01 · B-3 no se puede verificar como estaba escrito.**
El plan exige `docker compose up` en una máquina limpia → `curl localhost:8080/api/v1/health` = 200.
Docker no está instalado. El `docker-compose.yml` se escribe igual (se necesita el 13 sep para AWS), pero **hoy no queda probado**: la validación de hoy es contra el PostgreSQL local.
*Deuda explícita:* el primer `docker compose up` real ocurrirá recién en el despliegue a AWS. Eso viola la regla "nunca más un deploy por primera vez" del hito H3.
*Mitigación recomendada:* instalar Docker Desktop antes del 11 de septiembre para que el ZIP generado y el compose se prueben en local antes del deploy.

**D-02 · Se invierte el orden de rutas del spike B-2.**
El plan pone React Native como ruta principal y Flutter como contingencia. Se ejecuta **Flutter primero**.
*Motivo:* la ruta RN exige Custom Dev Client vía EAS Build (cuenta Expo, compilación en la nube, 20–40 min por iteración). Flutter ya está instalado y llega a evidencia con un `flutter run`. El criterio de aceptación de B-2 no es cuál framework es superior, sino **si hay texto transcrito en modo avión**; la ruta más corta a esa evidencia es la correcta.
*Reversibilidad:* total. Si Flutter no transcribe offline, se ejecuta la ruta RN sin haber perdido más de una hora.

### Reparto del día

| Sesión | Track | Alcance |
| :--- | :--- | :--- |
| `[planner]` | Coordinación | Repo, ramas, bitácora, control de hitos |
| `[cl1]` | A — Backend | B-3 esqueleto + **B-1 canónico** + 3 diagramas de referencia + round-trip + JPA/Flyway |
| `[cl2]` | B — Móvil/IA | **B-2 spike de voz offline** + veredicto de stack |

### Estructura del repositorio

Repositorio Git propio inicializado en `Productividad/` (antes estos archivos colgaban del repo accidental de `C:\Users\Daniel`).
Ramas: `main` (protegida, solo recibe merges en hitos) · `develop` (rama de trabajo activa).

> **Nota sobre la regla 1 del plan** ("nada se desarrolla en `main`, rama por track"): las tres sesiones comparten un único *worktree*, y Git no permite tres ramas activas a la vez sobre el mismo directorio. Se cumple el espíritu de la regla con una sola rama `develop` y **separación por carpetas disjuntas** (`backend/` para Track A, `spike-voz/` + `mobile/` para Track B). `main` solo recibe merge en H1–H5.

### Bloqueo que depende de Daniel

El spike B-2 **no es válido en emulador**. Requiere un celular Android físico con:
1. Depuración USB activada y conectado.
2. El paquete de reconocimiento de voz **en español para uso sin conexión** descargado.
3. Modo avión activado al momento de la prueba.

---
