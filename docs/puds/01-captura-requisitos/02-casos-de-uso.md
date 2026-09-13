# 2.1.2 Casos de uso

> Modelo de casos de uso — PUDS.
> Identifica **qué casos de uso existen**, a qué requisito responde cada uno y en qué parte
> del código vive. La especificación detallada de flujos es la sección 2.3.

---

## Criterio de granularidad

Un caso de uso es **un objetivo de un actor**, no una operación técnica. `POST /projects` y
`GET /projects` no son dos casos de uso: son dos pasos del mismo objetivo — *gestionar mis
proyectos*.

Por eso el catálogo agrupa las operaciones de alta, consulta, modificación y baja de un
mismo concepto en **un solo caso de uso**, y el detalle de cada operación aparece como
**flujo alternativo** dentro de su especificación (sección 2.3), que es donde el Proceso
Unificado espera encontrarlo.

Donde **no** se agrupa, y el motivo:

| Caso | Por qué queda separado |
| :--- | :--- |
| CU10 Gestionar colaboradores / **CU11 Aceptar invitación** | **Actores distintos**: el propietario invita, el invitado acepta. Dos objetivos, dos actores |
| CU13 Validar diagrama / **CU14 Generar backend** | Es un `«include»`, y en UML un `«include»` **es** un caso de uso propio. Además tiene valor solo: se puede validar sin descargar nada |
| CU03 Modelar clases / **CU04 Modelar relaciones** | Las relaciones tienen semántica propia —multiplicidad, lado propietario, navegabilidad— que decide el esquema de base de datos generado |

Resultado: **19 casos de uso**, de los cuales **15 entran en el parcial** (ciclos 1 a 3).

---

## Paquetes de casos de uso

Agrupaciones por objetivo del usuario. **Coinciden casi uno a uno con los paquetes del
backend**, y eso no es casualidad: la estructura modular de §6 se derivó de los mismos
requisitos.

| Paquete | Casos de uso | Paquete Java |
| :--- | :--- | :--- |
| **A.** Gestión de acceso | CU01, CU02 | `auth/` |
| **B.** Gestión de proyectos | CU08, CU09, CU10, CU11 | `project/`, `diagram/` |
| **C.** Modelado | CU03, CU04, CU15 | `metamodel/` + interfaz web |
| **D.** Colaboración en tiempo real | CU05, CU06, CU07 | `collaboration/` |
| **E.** Trabajo sin conexión | CU12 | `diagram/service/` |
| **F.** Generación de código | CU13, CU14, CU19 | `generator/` |
| **G.** Interoperabilidad | CU18 | `xmi/` |
| **H.** Asistencia por IA | CU16, CU17 | `ai/`, `vision/` |

---

## Catálogo

**Estado:** 🟢 implementado y verificado · 🟡 parcial · ⬜ no implementado

### Paquete A — Gestión de acceso

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU01** | Registrarse | Visitante | derivado | 1 | 🟢 |
| **CU02** | Iniciar sesión | Visitante | derivado | 1 | 🟢 |

**Implementación:** `POST /api/v1/auth/register`, `POST /api/v1/auth/login` —
`auth/controller/AuthController`.

> **No trazan a ningún RF, y hay que saber explicarlo.** Son **requisitos derivados**:
> aparecen porque RF-04 exige roles por colaborador, y no hay roles sin identidad. No se
> agregaron "porque toda aplicación tiene login", sino porque otro requisito los obliga.

---

### Paquete B — Gestión de proyectos

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU08** | Gestionar proyectos | Colaborador | RF-04 | 2 | 🟢 |
| **CU09** | Gestionar diagramas | Propietario, Editor | RF-01 | 2 | 🟢 |
| **CU10** | Gestionar colaboradores del proyecto | Propietario | RF-04 | 2 | 🟢 |
| **CU11** | Aceptar invitación | Visitante, Colaborador | RF-04 | 2 | 🟢 |

| # | Operaciones que absorbe | Endpoints |
| :--- | :--- | :--- |
| CU08 | Crear proyecto · Consultar mis proyectos | `POST` y `GET /api/v1/projects` |
| CU09 | Crear diagrama · Consultar diagramas · Abrir diagrama | `POST` y `GET /api/v1/projects/{id}/diagrams`, `GET /api/v1/diagrams/{id}/snapshot` |
| CU10 | Generar enlace de invitación · Consultar invitaciones · Revocar invitación · Agregar miembro por correo · Consultar miembros | `POST`/`GET`/`DELETE …/invitations`, `POST`/`GET …/members` |
| CU11 | Consultar una invitación · Aceptarla | `GET /api/v1/invitations/{token}`, `POST …/accept` |

> **CU10 y CU11 no estaban en la especificación original.** §14 lista crear y listar
> proyectos, pero **ningún endpoint para otorgar un rol**. El sistema tenía tres roles
> definidos, arbitrados y probados… y ninguna forma de asignarlos salvo escribir una fila en
> la base de datos a mano.
>
> El hueco apareció **al recorrer el catálogo completo de casos de uso**, que es exactamente
> para lo que sirve esta etapa del proceso. Resuelto con la migración `V3`.

> **Dos reglas de CU11 que se defienden.** Aceptar una invitación **nunca baja** un rol
> existente: si el propietario abre su propio enlace de lector, sigue siendo propietario —
> si no, se degradaría a sí mismo sin poder recuperarse. Y aceptar es **idempotente**: abrir
> el enlace tres veces no agrega tres miembros.

---

### Paquete C — Modelado

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU03** | Modelar clases y sus miembros | Editor, Propietario | RF-01 | 1 | 🟡 |
| **CU04** | Modelar relaciones | Editor, Propietario | RF-01 | 1 | 🟡 |
| **CU15** | Alternar vista UML / Entidad-Relación | Colaborador | RF-01 | 3 | ⬜ |

| # | Operaciones que absorbe | Estado por operación |
| :--- | :--- | :--- |
| CU03 | `ADD_CLASS`, `MOVE_CLASS`, `RESIZE_CLASS`, `RENAME_CLASS`, `DELETE_CLASS` | 🟢 |
| | `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE`, `DELETE_ATTRIBUTE` | 🟢 |
| | `ADD_METHOD`, `UPDATE_METHOD`, `DELETE_METHOD` | 🟡 servidor sí, interfaz no |
| | `ADD_PACKAGE`, `UPDATE_PACKAGE`, `DELETE_PACKAGE` | 🟡 servidor sí, interfaz no |
| CU04 | `ADD_RELATIONSHIP`, `DELETE_RELATIONSHIP`, `UPDATE_WAYPOINTS` | 🟢 |
| | `UPDATE_RELATIONSHIP` | ⬜ |

> **La agrupación reveló algo que el catálogo anterior escondía.** Separados, "modelar
> clases" y "modelar atributos" figuraban completos y "modelar métodos" figuraba aparte como
> pendiente. Juntos en un solo objetivo, **CU03 queda como parcial** — que es la verdad: un
> usuario que quiere modelar una clase completa todavía no puede agregarle métodos desde la
> interfaz.

> **El detalle de CU04 que decide el código generado:** el **lado propietario** de la
> relación determina en qué tabla va la clave foránea. La interfaz lo deriva de las
> multiplicidades —manda el extremo que no es `1..1`— y permite cambiarlo, pero no obliga al
> usuario a entenderlo para dibujar. Si tuviera que elegirlo a mano, la mitad de los
> diagramas saldrían con el esquema invertido.

---

### Paquete D — Colaboración en tiempo real

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU05** | Editar el diagrama en colaboración | Editor, Propietario | RF-04 | 1 | 🟢 |
| **CU06** | Gestionar bloqueo de elemento | Editor, Propietario, **Tiempo** | RF-04 | 1 | 🟢 |
| **CU07** | Visualizar presencia y cursores | Colaborador | RF-04 | 1 | 🟢 |

| # | Operaciones que absorbe | Destino |
| :--- | :--- | :--- |
| CU05 | Enviar mutación · Difundir a la sala · Rechazar por rol · Descartar duplicados | `/app/diagram/{id}/mutate` → `/topic/diagrams/{id}` |
| CU06 | Adquirir bloqueo · Renovar · Liberar explícitamente · **Expirar por tiempo** · Liberar por desconexión | `/app/diagram/{id}/lock`, `LockManagerService` |
| CU07 | Emitir cursor · Consultar participantes · Alta y baja de la sala | `/app/diagram/{id}/cursor` y `/presence` |

> **La expiración del bloqueo es un flujo alternativo de CU06, no un caso de uso aparte.**
> El disparador es el actor **Tiempo** —nadie pulsa nada— pero el objetivo es el mismo:
> que el elemento quede libre. Sin ese flujo, un usuario que cierra el navegador dejaría un
> elemento bloqueado para siempre.

> **Política de fallo rápido sin cola.** Si el elemento está tomado, el solicitante recibe
> el rechazo y **no espera**: no hay cola ni reintento automático. Es lo que garantiza
> ausencia de interbloqueo e inanición, porque ningún cliente queda nunca esperando a otro.

---

### Paquete E — Trabajo sin conexión

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU12** | Trabajar sin conexión y sincronizar | Editor, Propietario | RF-05 | 3 | 🟡 |

Absorbe: modelar con la red caída · encolar las operaciones · detectar la reconexión ·
enviar el lote · **reconciliar contra el estado actual** · mostrar el informe.
Endpoint: `POST /api/v1/diagrams/{id}/sync-offline`.

> **El servidor ya lo resuelve completo**: aplica lo que puede, descarta lo que colisiona y
> devuelve un informe que nombra los elementos **por su nombre**, no por identificador —
> *"se descartó el atributo 'teléfono' porque otro colaborador eliminó la clase 'Cliente'"*.
> Falta la mitad del navegador: la cola y el diálogo.

> **Sin conexión, el cliente omite la adquisición de bloqueo** y aplica todo de forma
> optimista. No es un agujero: la exclusión mutua no desaparece, **se traslada del momento
> de la edición al momento de la reconciliación**. El sistema usa prevención cuando hay
> árbitro en línea y resolución cuando no lo hay.

---

### Paquete F — Generación de código

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU13** | Validar diagrama | Colaborador | RF-06 | 3 | ⬜ |
| **CU14** | Generar backend Spring Boot | Colaborador | RF-06 | 3 | 🟡 |
| **CU19** | Generar app móvil con asistente de voz | Colaborador, *Motor STT* | RF-07, RF-08 | 5 | ⬜ |

> **CU13 es precondición de CU14 y no un adorno.** El botón de descarga ejecuta primero la
> validación y, si hay errores bloqueantes —una relación huérfana, herencia múltiple—
> muestra el diálogo e **impide la descarga**. Generar un paquete que no compila es peor que
> no generarlo.

> **Estado real de CU14:** las plantillas de las cinco capas están escritas y hay un arnés
> que **genera y compila** los cuatro diagramas de prueba. Falta el empaquetado en ZIP y el
> endpoint que lo entrega.

---

### Paquete G — Interoperabilidad

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU18** | Intercambiar modelos vía XMI 2.1 | Colaborador, *Enterprise Architect* | RF-09 | 4 | ⬜ |

Absorbe exportar e importar. Se agrupa porque el objetivo es uno —**interoperar con una
herramienta CASE**— y el criterio de aceptación también: exportar, abrir en Enterprise
Architect, reimportar y no perder nada. La ida sin la vuelta no demuestra nada.

---

### Paquete H — Asistencia por IA

| # | Caso de uso | Actor principal | RF | Ciclo | Estado |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **CU16** | Modelar por comando de voz o texto | Editor, Propietario, *Gemini* | RF-02 | 4 | ⬜ |
| **CU17** | Importar diagrama desde foto | Editor, Propietario, *Gemini* | RF-03 | 4 | ⬜ |

CU16 agrupa voz y texto porque **son el mismo caso de uso con distinto dispositivo de
entrada**: la voz se transcribe en el navegador y entra por el mismo endpoint que el texto.
El dictado es un flujo alternativo, no un objetivo distinto.

---

## Relaciones entre casos de uso

### «include» — lo obligatorio

| Caso base | incluye | Por qué |
| :--- | :--- | :--- |
| **CU05** Editar en colaboración | **CU06** Gestionar bloqueo | Renombrar, borrar o editar miembros exige poseer el bloqueo. Mover y redimensionar **no** |
| **CU14** Generar backend | **CU13** Validar diagrama | La validación corre siempre antes de empaquetar |
| **CU17** Importar desde foto | **CU05** Editar en colaboración | La propuesta aceptada entra como una mutación atómica |

> El `«include»` de CU05 → CU06 es **condicional según la operación**, y eso un diagrama no
> lo puede expresar. Está resuelto en el código en un solo lugar, el enumerado
> `OperationType`, donde cada operación declara su régimen: `NO_REQUIERE`, `LWW_LIBRE`,
> `REQUIERE_LOCK` o `LOCK_DE_DIAGRAMA`. Es la clase de detalle que el diagrama pierde y el
> documento tiene que recuperar.

### «extend» — lo opcional

| Caso extensión | extiende a | Cuándo |
| :--- | :--- | :--- |
| **CU12** Trabajar sin conexión | **CU05** Editar en colaboración | Sólo si se pierde la conexión con cambios pendientes |
| **CU11** Aceptar invitación | **CU02** Iniciar sesión | Si el invitado no tiene sesión, entra y **vuelve al enlace** |

### Generalización de actores

Propietario, Editor y Lector **son** Colaboradores, y heredan CU02, CU08 y CU09 sin
repetirlos.

---

## Matriz de trazabilidad: requisito → casos de uso

| RF | Requisito | Casos de uso | Estado |
| :---: | :--- | :--- | :---: |
| **RF-01** | Modelado visual manual (UML y ER) | CU03, CU04, CU09, CU15 | 🟡 faltan la vista ER y la interfaz de métodos y paquetes |
| **RF-02** | Modelado por voz y texto en la web | CU16 | ⬜ |
| **RF-03** | Modelado por visión (fotos) | CU17 | ⬜ |
| **RF-04** | Colaboración en tiempo real y exclusión mutua | CU05, CU06, CU07, CU08, CU10, CU11 | 🟢 |
| **RF-05** | Modo sin conexión y rebase | CU12 | 🟡 sólo el servidor |
| **RF-06** | Generador de backend Spring Boot | CU13, CU14 | 🟡 plantillas listas, falta el endpoint |
| **RF-07** | App móvil generada con asistente de voz | CU19 | ⬜ |
| **RF-08** | IA local en el dispositivo, sin internet | CU19 | 🟡 viabilidad probada en el spike |
| **RF-09** | Interoperabilidad XMI 2.1 | CU18 | ⬜ |
| — | *(derivado de RF-04)* | CU01, CU02 | 🟢 |

---

## Resumen

| | Total | 🟢 | 🟡 | ⬜ |
| :--- | :---: | :---: | :---: | :---: |
| **Ciclo 1** | 7 | 5 | 2 | 0 |
| **Ciclo 2** | 4 | 4 | 0 | 0 |
| **Ciclo 3** | 4 | 0 | 2 | 2 |
| **Ciclos 4–5** | 4 | 0 | 0 | 4 |
| **Total** | **19** | **9** | **4** | **6** |

**En el parcial: 15 casos de uso.** El núcleo colaborativo (RF-04) está completo y
verificado; lo pendiente se concentra en generación, trabajo sin conexión e IA.

---

## Diagramas

Fuentes en [`../diagramas/`](../diagramas/):

| Diagrama | Archivo |
| :--- | :--- |
| Contexto general — actores y paquetes | `casos-de-uso-general.puml` |
| Ciclo 1 — acceso, modelado y colaboración | `casos-de-uso-ciclo1.puml` |
| Ciclo 2 — proyectos y colaboradores | `casos-de-uso-ciclo2.puml` |
| Ciclo 3 — generación y trabajo sin conexión | `casos-de-uso-ciclo3.puml` |
| Ciclos 4 y 5 — IA, XMI y app móvil | `casos-de-uso-ciclo45.puml` |
