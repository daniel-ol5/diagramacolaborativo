# 1. Actores del sistema

> Modelo de casos de uso — PUDS, fase de Inicio.
> Fuente: `PLAN_ARQUITECTONICO_FASE_0.txt` §3 (RF-01…RF-09) y el código de
> `backend/src/main/java/com/modelcollab/`.

---

## 1.1 Qué es un actor, y por qué importa distinguirlo bien

Un **actor** no es una persona: es un **rol** que algo o alguien juega al interactuar con
el sistema. Tres consecuencias que se aplican en este documento:

1. **Una misma persona puede ser varios actores.** Daniel es *Propietario* del proyecto
   que creó y *Lector* de uno al que lo invitaron. No son dos usuarios: son dos roles.
2. **Un actor puede no ser humano.** Un sistema externo o un temporizador que dispara algo
   por sí solo también son actores.
3. **Lo que está adentro del sistema no es actor.** La base de datos no es un actor: es
   parte del sistema.

---

## 1.2 Actores humanos

### Visitante

Quien todavía no se identificó ante el sistema. Es el único actor que existe **antes** de
que haya una cuenta.

| | |
| :--- | :--- |
| **Puede** | Registrarse, iniciar sesión, y **consultar una invitación** para ver a qué proyecto lo invitan antes de aceptar |
| **No puede** | Ver ningún diagrama ni proyecto |
| **En el código** | `auth/controller/AuthController`, y `GET /api/v1/invitations/{token}`, que es el único endpoint público fuera de autenticación |

> **Por qué la consulta de invitación es pública.** Si exigiera sesión, el invitado vería
> un formulario de acceso sin saber a qué lo invitaron ni quién. La consulta devuelve sólo
> el nombre del proyecto y el rol ofrecido — nunca su contenido.

---

### Colaborador *(actor abstracto)*

Usuario autenticado. **No se instancia directamente**: siempre actúa con uno de los tres
roles de abajo. Se modela como actor abstracto porque hay casos de uso que valen para los
tres y repetirlos tres veces ensucia el diagrama.

| | |
| :--- | :--- |
| **Puede** | Iniciar sesión, ver sus proyectos, crear proyectos propios, aceptar invitaciones |
| **En el código** | Cualquier petición con `Authorization: Bearer`, validada por `config/JwtAuthenticationFilter` |

**El rol es por proyecto, no por usuario.** Esto es central y conviene decirlo explícito:
la tabla `project_members` asocia `(proyecto, usuario) → rol`. No existe un "usuario
editor" en el sistema; existe "editor **de este proyecto**". Quien crea un proyecto es su
Propietario, y en otro puede ser Lector.

---

### Propietario — `OWNER`

Quien creó el proyecto, o quien recibió ese rol.

| | |
| :--- | :--- |
| **Puede** | Todo lo de Editor, **más** compartir por enlace, agregar miembros por correo, revocar invitaciones |
| **En el código** | `project/service/ProjectService`, donde las operaciones de otorgamiento exigen `OWNER` |

---

### Editor — `EDITOR`

| | |
| :--- | :--- |
| **Puede** | Todo lo de Lector, **más** modelar: crear, mover, renombrar y borrar clases, atributos, métodos y relaciones; tomar bloqueos; crear diagramas |
| **No puede** | Invitar ni revocar |
| **En el código** | `collaboration/interceptor/StompChannelInterceptor` deja pasar sus mutaciones |

---

### Lector — `VIEWER`

| | |
| :--- | :--- |
| **Puede** | Abrir el diagrama, ver los cambios de los demás **en vivo**, ver los bloqueos ajenos, ver quién está en la sala, **y emitir su propio cursor** |
| **No puede** | Ninguna mutación. El servidor la descarta y le responde `403 FORBIDDEN_ROLE` por su canal privado |
| **En el código** | `StompChannelInterceptor`, verificado por `StompChannelInterceptorTest` |

> **Dos decisiones sobre el Lector que se defienden y no son obvias.**
>
> **Rechazar no lo desconecta.** El servidor descarta la mutación y le avisa por un canal
> privado, pero **mantiene la sesión abierta**. Si la cerrara, el Lector dejaría de ver el
> trabajo de los demás — que es exactamente lo único que sí tiene permitido.
>
> **Un Lector sí emite cursor y sí pide la lista de participantes.** Mirar dónde está
> mirando alguien que mira es correcto. Si no pudiera, entraría a una sala colaborativa y
> no vería a nadie.

---

## 1.3 Actores no humanos

### Tiempo *(temporizador del sistema)*

Actor que dispara casos de uso sin que nadie lo pida.

| | |
| :--- | :--- |
| **Dispara** | La expiración de un bloqueo con TTL de 5 s, y la caducidad de una invitación |
| **En el código** | El barrido periódico de `collaboration/service/LockManagerService` |

> Se modela como actor porque **inicia** un caso de uso: nadie pulsa nada y el sistema
> libera el bloqueo y se lo notifica a toda la sala. Sin este actor, el caso de uso
> "liberar bloqueo vencido" no tendría quién lo empiece, y es el que garantiza que un
> usuario que cierra el navegador no deje un elemento bloqueado para siempre.

---

### Servicio de IA generativa — *Gemini*

Sistema externo consultado para interpretar órdenes en lenguaje natural y para leer fotos
de pizarra.

| | |
| :--- | :--- |
| **Participa en** | Modelado por texto y voz (RF-02), importación desde foto (RF-03) |
| **Estado** | ⬜ **No integrado.** Los paquetes `ai/` y `vision/` existen en la estructura, vacíos |

---

### Herramienta CASE externa — *Sparx Enterprise Architect*

| | |
| :--- | :--- |
| **Participa en** | Exportación e importación XMI 2.1 (RF-09) |
| **Estado** | ⬜ **No implementado.** El paquete `xmi/` existe vacío |

> Es actor y no simple formato de archivo porque **el intercambio es bidireccional**: el
> sistema exporta para que la herramienta lea, e importa lo que la herramienta produjo. Un
> formato que sólo se escribe no necesita actor; uno que se negocia con otro sistema, sí.

---

### Motor de reconocimiento de voz del dispositivo

| | |
| :--- | :--- |
| **Participa en** | El asistente por voz de la **app móvil generada** (RF-07, RF-08) |
| **Estado** | 🟡 **Validado, no integrado.** El spike del Día 1 comprobó en un teléfono real, en modo avión, que transcribe sin internet — evidencia en `evidencias/spike-voz-modo-avion.png` |

> Es actor del **sistema generado**, no de ModelCollab. Aparece acá porque el caso de uso
> "generar app móvil" tiene que producir código que hable con él.

---

## 1.4 Diagrama de actores

Fuente: [`../diagramas/actores.puml`](../diagramas/actores.puml)

```
                    ┌──────────────┐
                    │  Visitante   │
                    └──────┬───────┘
                           │ (se registra / inicia sesión)
                           ▼
                    ┌──────────────┐
                    │ Colaborador  │  «abstracto»
                    └──────┬───────┘
             ┌─────────────┼─────────────┐
             ▲             ▲             ▲
      ┌──────┴─────┐ ┌─────┴──────┐ ┌────┴───────┐
      │ Propietario│ │   Editor   │ │   Lector   │
      │   OWNER    │ │   EDITOR   │ │   VIEWER   │
      └────────────┘ └────────────┘ └────────────┘

   NO HUMANOS:   [Tiempo]   [Gemini]   [Enterprise Architect]   [Motor STT]
```

La flecha de generalización se lee **"es un"**: un Propietario *es un* Colaborador, así que
hereda todos sus casos de uso. Eso evita repetir *"ver mis proyectos"* tres veces.

**Ojo con un error frecuente:** la generalización entre Propietario, Editor y Lector
**no** es una jerarquía de permisos acumulativos declarada en el diagrama. El diagrama
dice que los tres son Colaboradores. Que un Propietario pueda hacer todo lo de un Editor
es una decisión del sistema, escrita en `ProjectService`, no algo que el diagrama imponga.

---

## 1.5 Resumen

| Actor | Tipo | Estado en el código |
| :--- | :--- | :---: |
| Visitante | Humano | 🟢 |
| Colaborador | Humano, abstracto | 🟢 |
| Propietario (`OWNER`) | Humano | 🟢 |
| Editor (`EDITOR`) | Humano | 🟢 |
| Lector (`VIEWER`) | Humano | 🟢 |
| Tiempo | Sistema | 🟢 |
| Gemini | Sistema externo | ⬜ |
| Enterprise Architect | Sistema externo | ⬜ |
| Motor STT del dispositivo | Sistema externo | 🟡 validado |

**Leyenda:** 🟢 implementado y verificado · 🟡 parcial · ⬜ no implementado
