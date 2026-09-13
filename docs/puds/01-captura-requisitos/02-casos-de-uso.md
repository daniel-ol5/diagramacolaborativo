# 2. Casos de uso

> Modelo de casos de uso — PUDS, fase de Inicio/Elaboración.
> Este documento identifica **qué casos de uso existen** y a qué requisito y a qué código
> corresponde cada uno. La especificación detallada de flujos es el documento siguiente.

---

## 2.1 Cómo se agruparon

Los casos de uso se organizan en **paquetes de casos de uso**, que no son carpetas del
código: son agrupaciones por objetivo del usuario. Se eligieron de modo que cada paquete
responda a una pregunta completa del negocio, y **coinciden casi uno a uno con los
paquetes del backend** — lo cual no es casualidad, sino consecuencia de que la estructura
modular de §6 se derivó de los mismos requisitos.

| Paquete de casos de uso | Paquete Java correspondiente |
| :--- | :--- |
| Gestión de acceso | `auth/` |
| Gestión de proyectos y diagramas | `project/`, `diagram/` |
| Colaboración en tiempo real | `collaboration/` |
| Modelado | `metamodel/` + la interfaz web |
| Trabajo sin conexión | `diagram/service/OfflineReconciliationService` |
| Generación de código | `generator/` |
| Interoperabilidad | `xmi/` |
| Asistencia por IA | `ai/`, `vision/` |

---

## 2.2 Catálogo de casos de uso

**Leyenda de estado:** 🟢 implementado y verificado · 🟡 parcial · ⬜ no implementado

### Paquete A — Gestión de acceso

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU01** | Registrar usuario | Visitante | — | `POST /api/v1/auth/register` | 🟢 |
| **CU02** | Iniciar sesión | Visitante | — | `POST /api/v1/auth/login` | 🟢 |

> CU01 y CU02 no trazan a ningún RF: son **requisitos derivados**. Aparecen porque RF-04
> exige roles por colaborador, y no hay roles sin identidad. Vale decirlo en la defensa: no
> se agregaron "porque toda aplicación tiene login", sino porque otro requisito los obliga.

---

### Paquete B — Gestión de proyectos y diagramas

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU03** | Crear proyecto | Colaborador | RF-04 | `POST /api/v1/projects` | 🟢 |
| **CU04** | Consultar mis proyectos | Colaborador | RF-04 | `GET /api/v1/projects` | 🟢 |
| **CU05** | Crear diagrama | Propietario, Editor | RF-01 | `POST /api/v1/projects/{id}/diagrams` | 🟢 |
| **CU06** | Consultar diagramas del proyecto | Colaborador | RF-01 | `GET /api/v1/projects/{id}/diagrams` | 🟢 |
| **CU07** | Abrir diagrama | Colaborador | RF-01 | `GET /api/v1/diagrams/{id}/snapshot` | 🟢 |

> **CU04 devuelve el rol propio en cada proyecto.** Es un detalle de diseño con
> consecuencia funcional: sin él, la interfaz tendría que preguntar por separado qué puede
> hacer en cada proyecto antes de dibujar un botón.

---

### Paquete C — Colaboración en tiempo real

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU08** | Compartir proyecto por enlace | Propietario | RF-04 | `POST /api/v1/projects/{id}/invitations` | 🟢 |
| **CU09** | Consultar invitación | Visitante, Colaborador | RF-04 | `GET /api/v1/invitations/{token}` | 🟢 |
| **CU10** | Aceptar invitación | Colaborador | RF-04 | `POST /api/v1/invitations/{token}/accept` | 🟢 |
| **CU11** | Agregar miembro por correo | Propietario | RF-04 | `POST /api/v1/projects/{id}/members` | 🟢 |
| **CU12** | Revocar invitación | Propietario | RF-04 | `DELETE …/invitations/{invId}` | 🟢 |
| **CU13** | Editar el diagrama en tiempo real | Editor, Propietario | RF-04 | STOMP `/app/diagram/{id}/mutate` | 🟢 |
| **CU14** | Bloquear elemento para edición exclusiva | Editor, Propietario | RF-04 | STOMP `/app/diagram/{id}/lock` | 🟢 |
| **CU15** | Liberar bloqueo vencido | **Tiempo** | RF-04 | `LockManagerService` (barrido periódico) | 🟢 |
| **CU16** | Mostrar presencia y cursores | Colaborador | RF-04 | STOMP `/app/diagram/{id}/cursor` y `/presence` | 🟢 |

> **CU08 a CU12 no estaban en la especificación original.** §14 lista crear y listar
> proyectos, pero **ningún endpoint para otorgar un rol**. O sea que el sistema tenía roles
> y ninguna forma de concederlos: la única manera de que dos personas colaboraran era
> insertar una fila a mano en la base de datos.
>
> Detectado el 6 de septiembre y resuelto con la migración `V3`. Queda anotado acá porque
> **es un hallazgo de la captura de requisitos, y ése es exactamente el trabajo de este
> documento**: recorrer los casos de uso completos revela los que faltan.

---

### Paquete D — Modelado

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU17** | Modelar clases | Editor, Propietario | RF-01 | `ADD_CLASS`, `MOVE_CLASS`, `RESIZE_CLASS`, `RENAME_CLASS`, `DELETE_CLASS` | 🟢 |
| **CU18** | Modelar atributos | Editor, Propietario | RF-01 | `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE`, `DELETE_ATTRIBUTE` | 🟢 |
| **CU19** | Modelar métodos | Editor, Propietario | RF-01 | `ADD_METHOD`, `UPDATE_METHOD`, `DELETE_METHOD` | 🟡 servidor sí, interfaz no |
| **CU20** | Modelar relaciones | Editor, Propietario | RF-01 | `ADD_RELATIONSHIP`, `DELETE_RELATIONSHIP`, `UPDATE_WAYPOINTS` | 🟢 |
| **CU21** | Organizar en paquetes | Editor, Propietario | RF-01 | `ADD_PACKAGE`, `UPDATE_PACKAGE`, `DELETE_PACKAGE` | 🟡 servidor sí, interfaz no |
| **CU22** | Alternar vista UML / Entidad-Relación | Colaborador | RF-01 | — | ⬜ |

> El servidor implementa **18 de las 22 operaciones** del catálogo §8.1; la interfaz web
> emite **9**. Las cuatro operaciones del servidor que faltan se rechazan con un código
> explícito (`OP_NOT_IMPLEMENTED`) en vez de fallar en silencio, y cada una está asociada
> al caso de uso que la necesita: `UPDATE_RELATIONSHIP` (CU20 avanzado), `BULK_MERGE`
> (CU30, importación desde foto y XMI) y `USER_CURSOR` — que **sí** está implementada
> (CU16).

---

### Paquete E — Trabajo sin conexión

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU23** | Modelar sin conexión | Editor, Propietario | RF-05 | Cola en IndexedDB (navegador) | ⬜ |
| **CU24** | Sincronizar y reconciliar al reconectar | Editor, Propietario | RF-05 | `POST /api/v1/diagrams/{id}/sync-offline` | 🟡 servidor sí, interfaz no |

> CU24 es el caso más interesante del sistema y **el servidor ya lo resuelve completo**:
> aplica lo que puede, descarta lo que colisiona y devuelve un informe legible que nombra
> los elementos por su nombre, no por identificador. Falta la mitad del navegador: la cola
> y el diálogo que muestra el informe.

---

### Paquete F — Generación de código

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU25** | Validar diagrama antes de generar | Colaborador | RF-06 | `GET /api/v1/diagrams/{id}/validate` | ⬜ |
| **CU26** | Generar backend Spring Boot | Colaborador | RF-06 | `POST …/generate-backend` | 🟡 plantillas sí, endpoint no |
| **CU27** | Generar app móvil con asistente de voz | Colaborador | RF-07, RF-08 | `POST …/generate-mobile` | ⬜ |

> **CU25 es precondición de CU26 y no un adorno.** El plan lo declara: el botón de
> descarga ejecuta primero la validación y, si hay errores bloqueantes —una relación
> huérfana, herencia múltiple— muestra el diálogo e **impide la descarga**. Generar un ZIP
> que no compila es peor que no generarlo.

---

### Paquete G — Interoperabilidad

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU28** | Exportar a XMI 2.1 | Colaborador | RF-09 | `GET …/export-xmi` | ⬜ |
| **CU29** | Importar desde XMI 2.1 | Colaborador, *Enterprise Architect* | RF-09 | `POST …/import-xmi` | ⬜ |

---

### Paquete H — Asistencia por IA

| # | Caso de uso | Actor principal | RF | Dónde vive | Estado |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **CU30** | Modelar por comando de texto | Editor, Propietario, *Gemini* | RF-02 | `POST …/ai-command` | ⬜ |
| **CU31** | Modelar por comando de voz | Editor, Propietario, *Gemini* | RF-02 | mismo endpoint, con Web Speech API | ⬜ |
| **CU32** | Importar diagrama desde foto | Editor, Propietario, *Gemini* | RF-03 | `POST …/vision-import` | ⬜ |

---

## 2.3 Relaciones entre casos de uso

Tres relaciones que no son decorativas: cada una registra una decisión.

### «include» — lo obligatorio

| Caso base | incluye | Por qué |
| :--- | :--- | :--- |
| CU13 Editar en tiempo real | **CU14** Bloquear elemento | Renombrar, borrar o tocar atributos exige poseer el bloqueo. Mover y redimensionar **no** |
| CU26 Generar backend | **CU25** Validar diagrama | La validación corre siempre antes de empaquetar |
| CU32 Importar desde foto | **CU13** Editar | La propuesta aceptada entra como una mutación atómica |

> El `include` de CU13 → CU14 es **condicional según la operación**, y eso no se puede
> dibujar. Está resuelto en el código en un solo lugar, `OperationType`, donde cada
> operación declara su régimen de exclusión: `NO_REQUIERE`, `LWW_LIBRE`, `REQUIERE_LOCK`,
> `LOCK_DE_DIAGRAMA`. Es la clase de detalle que el diagrama pierde y el documento debe
> recuperar.

### «extend» — lo opcional

| Caso extensión | extiende a | Cuándo |
| :--- | :--- | :--- |
| CU24 Reconciliar | CU13 Editar en tiempo real | Sólo si hubo desconexión con cambios encolados |
| CU15 Liberar bloqueo vencido | CU14 Bloquear elemento | Sólo si el poseedor deja de renovar el bloqueo |

### Generalización de actores

Propietario, Editor y Lector **son** Colaboradores, y por eso heredan CU02, CU04, CU07
y CU10 sin que haya que repetirlos.

---

## 2.4 Matriz de trazabilidad: requisito → casos de uso

Esta tabla es la que responde *"¿dónde está implementado el requisito RF-nn?"*, y también
la que deja ver, sin discusión, qué falta.

| RF | Requisito | Casos de uso | Estado |
| :---: | :--- | :--- | :---: |
| **RF-01** | Modelado visual manual (UML y ER) | CU05 a CU07, CU17 a CU22 | 🟡 falta la vista ER y la interfaz de métodos y paquetes |
| **RF-02** | Modelado por voz y texto en la web | CU30, CU31 | ⬜ |
| **RF-03** | Modelado por visión (fotos) | CU32 | ⬜ |
| **RF-04** | Colaboración en tiempo real y exclusión mutua | CU03, CU04, CU08 a CU16 | 🟢 |
| **RF-05** | Modo sin conexión y rebase | CU23, CU24 | 🟡 sólo el servidor |
| **RF-06** | Generador de backend Spring Boot | CU25, CU26 | 🟡 plantillas parciales |
| **RF-07** | App móvil generada con asistente de voz | CU27 | ⬜ |
| **RF-08** | IA local en el dispositivo, sin internet | CU27 | 🟡 viabilidad probada en el spike |
| **RF-09** | Interoperabilidad XMI 2.1 | CU28, CU29 | ⬜ |

**Estado global: 16 de 32 casos de uso implementados y verificados; 6 parciales; 10 sin
empezar.** El núcleo colaborativo (RF-04) está completo; lo pendiente se concentra en
generación, IA e interoperabilidad.

---

## 2.5 Diagramas de casos de uso

Fuentes en [`../diagramas/`](../diagramas/):

| Diagrama | Archivo |
| :--- | :--- |
| Contexto general | `casos-de-uso-general.puml` |
| Paquete A+B — acceso, proyectos y diagramas | `casos-de-uso-acceso.puml` |
| Paquete C — colaboración en tiempo real | `casos-de-uso-colaboracion.puml` |
| Paquete D+E — modelado y trabajo sin conexión | `casos-de-uso-modelado.puml` |
| Paquete F+G+H — generación, interoperabilidad e IA | `casos-de-uso-generacion.puml` |

Se dividió en cinco diagramas y no uno solo por una razón práctica: **32 casos de uso y 9
actores en una sola hoja no se leen**. El diagrama de contexto muestra actores y paquetes;
cada diagrama por paquete muestra el detalle.

---

## 2.6 Lo que este recorrido dejó al descubierto

El valor de escribir el catálogo completo no es el catálogo: son los huecos que aparecen al
recorrerlo.

1. **No había forma de otorgar un rol** (CU08 a CU12). El sistema tenía tres roles
   definidos, arbitrados y probados… y ninguna manera de asignarlos salvo escribir en la
   base a mano. Es el hueco más grave que apareció, y estaba en la especificación original.
2. **CU19 y CU21 están partidos**: el servidor sabe manipular métodos y paquetes, la
   interfaz no los expone. Un caso de uso a medio camino es invisible para el usuario, que
   es lo mismo que no existir.
3. **CU22 (vista ER) no tiene ni servidor ni interfaz**, pero es parte de RF-01, que es
   el requisito que le da nombre al proyecto.

Los tres estaban en el código —o ausentes de él— desde antes. Ninguno se veía hasta
hacer la tabla.
