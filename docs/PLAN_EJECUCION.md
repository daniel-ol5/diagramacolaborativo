# Plan de Ejecución — 3 al 23 de septiembre (20 días)

> Documento operativo. Responde **qué construyo hoy**, **en qué orden** y **cómo sé que terminé**.
> La especificación de *qué existirá al final* está en `PLAN_ARQUITECTONICO_FASE_0.txt`; este documento no la repite, la ejecuta.

---

## 0. Cómo leer este plan

- **Track A / B / C** son las tres líneas de trabajo paralelizables. Si el equipo es de 3 personas, una por track. Si son 2, A+C juntos y B aparte. **Si trabajás solo, ejecutá los tracks en el orden A → C → B**, y aceptá que el alcance de la Fase 4 (visión) es lo primero que se recorta.
- Cada día tiene un **criterio de aceptación verificable**. Si al terminar el día no podés ejecutar la comprobación, vas atrasado — no es opinable.
- Los **hitos H1–H5** son puntos de integración obligatorios. No se avanza de fase con un hito rojo.

---

## 1. Los tres bloqueos que se resuelven ANTES de programar nada

Estas tres tareas no producen funcionalidad visible. Se hacen igual, primero, porque cada una puede invalidar semanas de trabajo posterior.

### B-1 · Congelar el modelo canónico (Día 1, 3 h) — **camino crítico absoluto**

El JSON de §7 bloquea todo: canvas, operaciones STOMP, generador Spring, XMI y app móvil. Todo lo demás se construye encima.

- Implementar los *records* Java del modelo canónico (`CanonicalModel`, `ClassEntity`, `Attribute`, `Method`, `Relationship`, `Package`) **exactamente** como el schema.
- Escribir 3 diagramas de referencia en JSON a mano: **barbería**, **veterinaria**, **inventario**. Estos tres archivos son los datos de prueba de todo el proyecto de aquí en adelante.
- Declarar el contrato **cerrado**. A partir del Día 2, cambiar el canónico exige revisar canvas + generador + XMI a la vez.

> **Aceptación:** los 3 JSON de referencia deserializan a los records Java sin pérdida de campos y vuelven a serializar idénticos (test de round-trip).

### B-2 · Spike de STT móvil offline (Día 1–2, 3 h) — **decisión irreversible**

Determina si el stack móvil es React Native o Flutter. Tomar esta decisión el 18 de septiembre sería fatal.

1. Crear proyecto RN con **Custom Dev Client** (`npx expo prebuild` + EAS Build), instalar `@react-native-voice/voice`.
2. **Celular físico Android**, no emulador. Ajustes → descargar paquete de reconocimiento de voz **en español** para uso sin conexión.
3. **Modo avión.** Dictar *"agenda una cita para mañana a las diez"*. Verificar que devuelve texto.

| Resultado | Decisión |
| :--- | :--- |
| Transcribe en modo avión | ✅ Ruta principal: React Native. Seguir. |
| No transcribe o el build falla | ⚠️ Ruta B: **Flutter + `speech_to_text`**. Repetir el spike (1 h). |
| Ninguna de las dos | 🔴 Ruta C: **Vosk** modelo ES (~45 MB) embebido. Sumar 1 día al cronograma y quitarlo de la Fase 4. |

> **Aceptación:** un texto transcrito, en pantalla, con el avión activado, en un teléfono real. Captura de pantalla guardada como evidencia para la defensa.

### B-3 · Esqueleto desplegable (Día 2, 2 h)

- `docker-compose.yml` con Spring Boot + PostgreSQL 16 levantando en local con un comando.
- Endpoint `GET /api/v1/health` respondiendo.
- Repositorio Git con las tres carpetas (`backend/`, `web/`, `mobile/`) y ramas por track.

> **Aceptación:** `docker compose up` en una máquina limpia → `curl localhost:8080/api/v1/health` responde `200`.

---

## 2. Cronograma diario

### FASE 1 — Núcleo colaborativo · 3 al 5 de septiembre

| Día | Track A (Backend/Colab) | Track B (Móvil/IA) | Criterio de aceptación del día |
| :---: | :--- | :--- | :--- |
| **Mié 3** | B-1 modelo canónico + entidades JPA + migraciones (`users`, `projects`, `diagrams`, `diagram_operations`) | **B-2 spike de voz** | Round-trip JSON ↔ Java OK. Spike con veredicto escrito. |
| **Jue 4** | JWT (login/register) + `StompChannelInterceptor` validando rol · B-3 esqueleto docker | Cierre del spike; decisión de stack móvil documentada | Un `VIEWER` recibe `403` al intentar mutar por STOMP. |
| **Vie 5** | `RoomManager` + `SequenceService` + `LockManagerService` (TTL 5 s, heartbeat, `LOCK_DENIED`) | Setup del proyecto móvil en el stack elegido | Dos clientes STOMP por consola: uno toma el lock, el segundo recibe `LOCK_DENIED`. |

**🚩 HITO H1 (5 sep):** *el servidor arbitra*. Salas STOMP vivas, mutaciones persistidas en JSONB, locks concedidos y denegados correctamente. Sin interfaz todavía.

---

### FASE 2 — Lienzo y colaboración visual · 6 al 10 de septiembre

| Día | Track A (Web/Canvas) | Track C (Generador) | Criterio de aceptación del día |
| :---: | :--- | :--- | :--- |
| **Sáb 6** | React + Vite + Zustand + React Flow. `UmlClassNode` con atributos y métodos. | — | Se dibuja una clase y se editan sus atributos localmente. |
| **Dom 7** | `UmlRelationshipEdge` (rombos, flechas, multiplicidades, waypoints). | — | Las 6 relaciones del canónico se renderizan distinguibles entre sí. |
| **Lun 8** | Cliente STOMP + optimistic update + filtrado de eco por `clientMutationId`. | Plantillas FreeMarker: Entidad JPA y Repositorio (capas 1–2). | **Dos navegadores, una clase movida en A aparece en B sin F5.** Cero parpadeo en el emisor. |
| **Mar 9** | Locks en UI (borde de color, candado, tooltip) + `CollaboratorPresence` (cursores). | Plantillas: DTO + Mapper + Service + Controller (capas 3–5) + `pom.xml` + Docker. | B ve el candado mientras A renombra; B no puede editar ese nodo. |
| **Mié 10** | `OfflineManager`: cola IndexedDB, máquina de estados, `OfflineSyncBadge` + endpoint de rebase con reporte de descartes. | `PreGenerationValidatorService` con el catálogo de §11.2. | Cortar red → 3 cambios → reconectar → modal de reconciliación con conteo aplicado/descartado. |

**🚩 HITO H2 (10 sep):** *la herramienta ya es una herramienta*. Modelado manual + colaboración + exclusión mutua + offline funcionando de punta a punta. **Este es el corazón del proyecto: si algo se atrasa, se atrasa lo demás, no esto.**

---

### FASE 3 — Generación e infraestructura · 11 al 13 de septiembre

| Día | Track A | Track C | Criterio de aceptación del día |
| :---: | :--- | :--- | :--- |
| **Jue 11** | Toggle **Vista UML / Vista ER** sobre el mismo store (PK/FK, patas de gallo). | `ZipPackagingService` + endpoint `generate-backend`. | Descargar ZIP de la barbería y compilarlo con `mvn clean compile`. |
| **Vie 12** | `BULK_MERGE` (op atómica para importaciones) + `UPDATE_RELATIONSHIP`. | **Test de humo en CI:** los 3 diagramas de referencia compilan automáticamente. | El build de CI falla si un diagrama de referencia no compila. |
| **Sáb 13** | Exportador e importador **XMI 2.1**. | **Despliegue en AWS EC2**: Nginx + SSL/WSS + Docker Compose. | Round-trip: exportar → abrir en Enterprise Architect → reimportar sin pérdidas. **URL pública con WSS funcionando.** |

**🚩 HITO H3 (13 sep):** *la aplicación está en internet y genera código que compila*. A partir de aquí, todo lo que se desarrolle se prueba directo en AWS, no en localhost. **Nunca más un deploy "por primera vez".**

---

### FASE 4 — Inteligencia artificial en la web · 14 al 17 de septiembre

| Día | Trabajo | Criterio de aceptación del día |
| :---: | :--- | :--- |
| **Dom 14** | Endpoint `/ai-command` + cliente Gemini con JSON Schema estricto y temperatura 0. **Primero el modo texto**, que es el que se puede testear sin micrófono. | Escribir *"crea la clase Factura con atributo total decimal"* → aparece en el lienzo de los dos navegadores. |
| **Lun 15** | Guardrail `GENERATE_DOMAIN` + tope de 5 operaciones por comando. Web Speech API sobre el mismo endpoint. `GridLayoutEngine` anti-colisión. | *"Creame un sistema para un banco"* → rechazo educado. Comando por voz equivalente al de texto. |
| **Mar 16** | `VisionOcrService` (Gemini multimodal) + `VisionModal` human-in-the-loop, insertando vía `BULK_MERGE`. | Foto de un diagrama en papel → modal con la propuesta → confirmar → un solo evento al lienzo. |
| **Mié 17** | Colchón de estabilización. Bugs de las fases 1–4. | Guión de demo §19 ejecutable hasta el minuto 7:00. |

**🚩 HITO H4 (17 sep):** *los tres modos de modelado funcionan sobre AWS*. Manual, voz/texto y foto.

> **Punto de recorte:** si hay atraso, lo que se sacrifica aquí es la **visión por foto (Día 16)**, no el guardrail ni el modo texto. La visión es impresionante pero es un requisito entre nueve; el guardrail es una pregunta directa del ingeniero.

---

### FASE 5 — App móvil generada con IA local · 18 al 20 de septiembre

| Día | Trabajo | Criterio de aceptación del día |
| :---: | :--- | :--- |
| **Jue 18** | `NluIntentGeneratorService`: derivar `intents.json` desde el metamodelo (CREAR/LISTAR/BUSCAR/ELIMINAR por entidad + slots por tipo de atributo). | Generar el `intents.json` de la barbería y de la veterinaria: intenciones distintas sin tocar código. |
| **Vie 19** | Plantillas de la app móvil + `IntentClassifier` + `SlotExtractor` (fechas relativas, horas, montos) + TTS. | En modo avión: dictar → intención y slots correctos → confirmación hablada. |
| **Sáb 20** | `LocalDatabase` (SQLite) + `SyncManager` + `ApiClient` contra el backend generado en AWS. | Modo avión: crear cita → queda `PENDIENTE_SYNC` → activar red → aparece en PostgreSQL. |

**🚩 HITO H5 (20 sep):** *el ciclo completo cierra*. Dibujo un diagrama → genero backend y app → la app opera el negocio por voz sin internet.

---

### FASE 6 — Endurecimiento y defensa · 21 al 23 de septiembre

| Día | Trabajo | Criterio de aceptación del día |
| :---: | :--- | :--- |
| **Dom 21** | `stomp_stress_test.js` con 20 clientes concurrentes. Corrección de fugas de locks y cuellos de botella. | 20 clientes/60 s: cero mensajes perdidos, cero locks huérfanos, p95 < 250 ms. |
| **Lun 22** | **Ensayo general cronometrado del guión de §19**, con el equipo y el hardware reales. Plan B de cada paso. | El guión completo corre en menos de 10 minutos sin intervención de emergencia. |
| **Mar 23** | Congelamiento de código. Solo se arreglan fallos que rompan la demo. Entrega. | Entrega formal. |

---

## 3. Reglas de ejecución

1. **Nada se desarrolla en `main`.** Rama por track, merge en los hitos.
2. **A partir del 13 de septiembre se prueba en AWS**, no en localhost. Los bugs de entorno aparecen temprano o aparecen en la defensa.
3. **El guión de demostración de §19 es la prueba de regresión.** Se corre completo en cada hito, no solo al final.
4. **Ningún hito rojo avanza de fase.** Si H2 no está el 10 de septiembre, se recorta la Fase 4, no se apila deuda.
5. **Todo lo que se demuestra debe existir en los 3 diagramas de referencia.** No se prepara un dominio especial para la demo — el tribunal puede pedir otro.

---

## 4. Presupuesto de riesgo y recortes

Si al **13 de septiembre (H3)** hay atraso, se recorta en este orden estricto. Cada recorte cuesta poco porque están ordenados de menor a mayor peso en la evaluación:

| Orden | Se recorta | Por qué es el primero en caer |
| :---: | :--- | :--- |
| 1 | Minimapa, auto-alineación y estética del canvas | Cosmético, no es requisito. |
| 2 | Métodos y parámetros en el generador (solo atributos) | El CRUD generado sigue compilando y funcionando. |
| 3 | **Visión por foto de pizarra** | Es 1 de 9 requisitos; sobreviven los otros dos modos de modelado. |
| 4 | Importación XMI (dejar solo exportación) | Media interoperabilidad es defendible; cero no lo es. |
| 5 | Vista Entidad-Relación | El enunciado admite "UML **o** ER". |

**Nunca se recorta:** colaboración en tiempo real, exclusión mutua, offline con rebase, generador Spring Boot compilable, ni la app móvil con voz offline. Esos cinco **son** el proyecto.

---

## 5. Tablero de estado

Actualizar al cierre de cada día. Es el único indicador honesto de si el plan se está cumpliendo.

| Hito | Fecha | Estado | Bloqueo |
| :--- | :---: | :---: | :--- |
| **B-1** Canónico congelado | 3 sep | ⬜ | |
| **B-2** Spike de voz resuelto | 4 sep | ⬜ | |
| **H1** El servidor arbitra | 5 sep | ⬜ | |
| **H2** Herramienta colaborativa completa | 10 sep | ⬜ | |
| **H3** En AWS + genera código que compila | 13 sep | ⬜ | |
| **H4** Tres modos de modelado | 17 sep | ⬜ | |
| **H5** Ciclo completo cerrado | 20 sep | ⬜ | |
| **Entrega** | 23 sep | ⬜ | |
