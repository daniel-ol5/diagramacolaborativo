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

## Actores no humanos

### Tiempo *(temporizador del sistema)*

| | |
| :--- | :--- |
| **Dispara** | La expiración de un bloqueo con TTL de 5 s, y la caducidad de una invitación |
| **En el código** | El barrido periódico de `collaboration/service/LockManagerService` |

> Es actor porque **inicia** un caso de uso: nadie pulsa nada y el sistema libera el bloqueo
> y se lo notifica a toda la sala. Sin él, el flujo "liberar bloqueo vencido" no tendría
> quién lo empiece — y un usuario que cierra el navegador dejaría un elemento bloqueado
> para siempre.

---

### Servicio de IA generativa — *Gemini*

| | |
| :--- | :--- |
| **Participa en** | CU16 modelado por voz y texto (RF-02), CU17 importación desde foto (RF-03) |
| **Estado** | ⬜ **No integrado.** Los paquetes `ai/` y `vision/` existen en la estructura, vacíos |

---

### Herramienta CASE externa — *Sparx Enterprise Architect*

| | |
| :--- | :--- |
| **Participa en** | CU18 intercambio XMI 2.1 (RF-09) |
| **Estado** | ⬜ **No implementado.** El paquete `xmi/` existe vacío |

> Es actor y no un simple formato de archivo porque **el intercambio es bidireccional**: el
> sistema exporta para que la herramienta lea, e importa lo que la herramienta produjo. Un
> formato que sólo se escribe no necesita actor; uno que se negocia con otro sistema, sí.

---

### Motor de reconocimiento de voz del dispositivo

| | |
| :--- | :--- |
| **Participa en** | CU19, el asistente por voz de la **app móvil generada** (RF-07, RF-08) |
| **Estado** | 🟡 **Validado, no integrado.** El spike del ciclo 1 comprobó en un teléfono real, en modo avión, que transcribe sin internet — evidencia en `evidencias/spike-voz-modo-avion.png` |

> Es actor del **sistema generado**, no de ModelCollab. Aparece acá porque CU19 tiene que
> producir código que hable con él.

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

   NO HUMANOS:   [ Tiempo ]   [ Gemini ]   [ Enterprise Architect ]   [ Motor STT ]
```

---

## Resumen

| Actor | Tipo | Estado |
| :--- | :--- | :---: |
| Visitante | Humano | 🟢 |
| Usuario | Humano | 🟢 |
| Tiempo | Sistema | 🟢 |
| Gemini | Sistema externo | ⬜ |
| Enterprise Architect | Sistema externo | ⬜ |
| Motor STT del dispositivo | Sistema externo | 🟡 validado |

**Leyenda:** 🟢 implementado y verificado · 🟡 parcial · ⬜ no implementado
