# 2.1.1 Actores del sistema

> Modelo de casos de uso — PUDS.
> Fuente: `PLAN_ARQUITECTONICO_FASE_0.txt` §3 (RF-01…RF-09) y el código de
> `backend/src/main/java/com/modelcollab/`.

---

## Qué es un actor

Un **actor** no es una persona: es un **rol externo al sistema** que interactúa con él para
alcanzar un objetivo. De ahí tres consecuencias:

1. **Una misma persona puede ser varios actores**, en momentos distintos.
2. **Un actor puede no ser humano.** Un sistema externo, o un temporizador que dispara algo
   por su cuenta, también lo son.
3. **Lo que está adentro del sistema no es actor.** La base de datos no es un actor: es
   parte del sistema.

---

## Actores humanos

### Visitante

Quien todavía no se identificó ante el sistema. Es el único actor que existe **antes** de
que haya una cuenta.

| | |
| :--- | :--- |
| **Objetivos** | Registrarse · Iniciar sesión · **Consultar una invitación** para saber a qué proyecto lo invitan antes de aceptar |
| **No puede** | Ver ningún proyecto ni diagrama |
| **En el código** | `auth/controller/AuthController`, y `GET /api/v1/invitations/{token}` — el único endpoint público fuera de autenticación |

> **Por qué consultar una invitación es público.** Si exigiera sesión, el invitado vería un
> formulario de acceso sin saber a qué lo invitan ni quién. La consulta devuelve sólo el
> nombre del proyecto y el rol ofrecido — **nunca su contenido**.

---

### Usuario

Persona autenticada. Es el actor principal del sistema: modela diagramas, gestiona sus
proyectos e invita a otros a colaborar.

| | |
| :--- | :--- |
| **Objetivos** | Crear y abrir proyectos y diagramas · Modelar clases, atributos, métodos y relaciones · Colaborar en tiempo real · Invitar colaboradores · Generar código |
| **En el código** | Cualquier petición con `Authorization: Bearer`, validada por `config/JwtAuthenticationFilter` y, sobre WebSocket, por `collaboration/interceptor/StompChannelInterceptor` |

---

## Por qué `OWNER`, `EDITOR` y `VIEWER` **no** son actores

El sistema define tres roles y los arbitra estrictamente. Sin embargo **no se modelan como
actores**, y conviene dejar escrito el criterio porque es una decisión de método:

> Un actor se separa de otro cuando persigue **objetivos distintos**, no cuando tiene
> **permisos distintos** sobre los mismos objetivos.

Aplicado a este sistema:

| Rol | ¿Tiene algún caso de uso propio? | Conclusión |
| :--- | :--- | :--- |
| `OWNER` | Sí: gestionar colaboradores. Pero es *administrar un proyecto*, no un oficio distinto | Mismo actor, más permisos |
| `EDITOR` | No. Persigue exactamente lo mismo que el lector: abrir el diagrama y trabajarlo | Mismo actor |
| `VIEWER` | **Ninguno.** Se define enteramente por lo que *no* puede hacer | Nivel de autorización |

Un rol que sólo existe **por negación** no es un actor: es un nivel de autorización.

### Dónde queda entonces esa información

No se pierde — **se muda a un lugar más preciso**. Un diagrama de casos de uso no puede
expresar que *mover* una clase no requiere bloqueo pero *renombrarla* sí; una tabla sí
puede. Los roles se documentan en tres lugares:

1. La **precondición** de cada caso de uso (sección 2.3)
2. La **matriz de permisos** de acá abajo
3. El **modelo de análisis y diseño**, donde el arbitraje aparece como clase de control

### El rol es por proyecto, no por usuario

Esto es central: la tabla `project_members` asocia **`(proyecto, usuario) → rol`**. No
existe "un usuario editor" en el sistema; existe "editor **de este proyecto**". El mismo
Usuario es `OWNER` del proyecto que creó y `VIEWER` de otro al que lo invitaron.

---

## Matriz de permisos

| Caso de uso | `OWNER` | `EDITOR` | `VIEWER` |
| :--- | :---: | :---: | :---: |
| CU07 Visualizar presencia y cursores | ✅ | ✅ | ✅ |
| CU08 Gestionar proyectos *(los propios)* | ✅ | ✅ | ✅ |
| CU09 Gestionar diagramas — consultar y abrir | ✅ | ✅ | ✅ |
| CU09 Gestionar diagramas — crear | ✅ | ✅ | ❌ |
| CU03 Modelar clases y sus miembros | ✅ | ✅ | ❌ |
| CU04 Modelar relaciones | ✅ | ✅ | ❌ |
| CU05 Editar el diagrama en colaboración | ✅ | ✅ | ❌ |
| CU06 Gestionar bloqueo de elemento | ✅ | ✅ | ❌ |
| CU12 Trabajar sin conexión y sincronizar | ✅ | ✅ | ❌ |
| CU10 Gestionar colaboradores del proyecto | ✅ | ❌ | ❌ |
| CU13 Validar diagrama | ✅ | ✅ | ✅ |
| CU14 Generar backend Spring Boot | ✅ | ✅ | ✅ |
| CU15 Alternar vista UML / ER | ✅ | ✅ | ✅ |

**Tres filas que hay que saber explicar:**

- **El `VIEWER` sí emite cursor y sí consulta la lista de participantes** (CU07). Si no
  pudiera, entraría a una sala colaborativa y no vería a nadie. Mirar dónde está mirando
  alguien que mira es correcto.
- **El `VIEWER` sí puede generar código** (CU14). Generar no modifica el diagrama: es una
  lectura que produce un archivo. Prohibírselo no protegería nada.
- **Rechazar a un `VIEWER` no lo desconecta.** El servidor descarta la mutación, le responde
  `403 FORBIDDEN_ROLE` por su canal privado y **mantiene la sesión abierta**. Si la cerrara,
  dejaría de ver el trabajo de los demás — que es exactamente lo único que sí tiene
  permitido.

### Granularidad que la matriz no alcanza a mostrar

Dentro de CU05, no todas las operaciones tienen el mismo régimen de exclusión mutua:

| Régimen | Operaciones |
| :--- | :--- |
| **No requiere bloqueo** | `ADD_CLASS`, `ADD_RELATIONSHIP`, `ADD_PACKAGE` |
| **Libre, gana el último** | `MOVE_CLASS`, `RESIZE_CLASS`, `UPDATE_WAYPOINTS` |
| **Requiere bloqueo** | `RENAME_CLASS`, `DELETE_*`, `ADD_ATTRIBUTE`, `UPDATE_*` |
| **Bloqueo del diagrama entero** | `BULK_MERGE` |

Está declarado en un solo lugar del código —el enumerado `OperationType`— y no repartido en
condicionales. Arrastrar una clase a 60 cuadros por segundo pidiendo bloqueo sería
inutilizable; renombrarla sin bloqueo produciría pérdidas silenciosas.

---

## Sistemas externos: por qué no figuran como actores del parcial

En UML **un sistema externo sí puede ser actor**: la definición es *"alguien o algo fuera
del sistema que interactúa con él"*. Si ModelCollab le envía una petición a un servicio de
IA y espera su respuesta, ese servicio está fuera de la frontera y participa del caso de
uso.

La distinción que conviene manejar es otra:

| | |
| :--- | :--- |
| **Actor primario** | Inicia el caso de uso y persigue un objetivo propio |
| **Actor de apoyo** | Sirve al caso de uso, pero nunca lo inicia |

Los tres sistemas externos de este proyecto son **de apoyo**, y todos participan
**únicamente en casos de uso posteriores al parcial**:

| Sistema | Participa en | Ciclo |
| :--- | :--- | :---: |
| Servicio de IA generativa (*Gemini*) | CU16 modelado por voz y texto · CU17 importación desde foto | 4 |
| Herramienta CASE (*Sparx Enterprise Architect*) | CU18 intercambio XMI 2.1 | 4 |
| Motor de reconocimiento de voz del dispositivo | CU19 app móvil generada | 4 |

Por eso **no aparecen en el modelo de casos de uso de los ciclos 1 a 3**. Se documentan
acá para dejar registrada la frontera del sistema, y se incorporan al modelo cuando se
aborden sus casos de uso.

> **Nota sobre el motor de voz.** Es actor del **sistema generado**, no de ModelCollab. Su
> viabilidad ya está probada: el spike del ciclo 1 comprobó en un teléfono real, en modo
> avión, que transcribe sin internet — evidencia en `evidencias/spike-voz-modo-avion.png`.

---

## El vencimiento del bloqueo: un evento temporal, no un actor

Hay una convención de RUP que modela el paso del tiempo como un actor llamado *Tiempo*,
para los casos de uso que nadie inicia. Acá aplicaría a uno: el bloqueo de un elemento
vence a los 5 segundos y el sistema avisa a toda la sala **sin que nadie pulse nada**.

**Se optó por no usar ese actor** y modelarlo como un **evento temporal** dentro del flujo
alternativo de CU06:

> **CU06 — Flujo alternativo B: el bloqueo vence**
> Transcurridos 5 000 ms sin que el poseedor renueve el bloqueo, el sistema lo libera y
> difunde `LOCK_RELEASED` a la sala.

No se pierde nada: el detalle vive en la especificación, que es donde se lee. Y el modelo
de casos de uso queda con **actores que son personas**, que es lo que se espera al leerlo.

> **Por qué ese flujo importa y no es un detalle.** Sin él, un usuario que cierra el
> navegador dejaría un elemento bloqueado para siempre, y nadie más podría editarlo. Es la
> diferencia entre un bloqueo y un candado sin llave.

---

## Diagrama de actores

Fuente: [`../diagramas/actores.puml`](../diagramas/actores.puml)

```
        ┌──────────────┐        se registra        ┌──────────────┐
        │  Visitante   │ ────── e inicia ─────────▶│   Usuario    │
        └──────────────┘         sesión            └──────────────┘
                                                    rol por proyecto:
                                                    OWNER · EDITOR · VIEWER
                                                    (autorización, no actores)
```

**Dos actores en todo el parcial.** Los sistemas externos y el evento temporal quedan fuera
por los motivos de arriba.

---

## Resumen

| Actor | Alcance | Estado |
| :--- | :--- | :---: |
| **Visitante** | Ciclos 1–3 *(parcial)* | 🟢 |
| **Usuario** | Ciclos 1–3 *(parcial)* | 🟢 |
| *Gemini* | Ciclo 4, actor de apoyo | ⬜ |
| *Enterprise Architect* | Ciclo 4, actor de apoyo | ⬜ |
| *Motor STT del dispositivo* | Ciclo 4, actor del sistema generado | 🟡 viabilidad probada |

**El modelo de casos de uso del parcial tiene dos actores: Visitante y Usuario.**

**Leyenda:** 🟢 implementado y verificado · 🟡 parcial · ⬜ no implementado
