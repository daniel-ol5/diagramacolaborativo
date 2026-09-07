# ModelCollab — Backend

API Spring Boot 3 del editor colaborativo de diagramas UML. La especificación completa
está en `../docs/PLAN_ARQUITECTONICO_FASE_0.txt`; este README cubre sólo cómo levantarlo y las
trampas conocidas.

---

## ⚠️ NO regeneres el proyecto desde start.spring.io

**El `pom.xml` está escrito a mano contra `spring-boot-starter-parent` 3.5.16 a propósito.**

start.spring.io ya no ofrece Spring Boot 3.5.x: su metadata sólo lista versiones 4.x. Si
regenerás el proyecto desde el Initializr vas a obtener un pom **incompatible** con este
código, porque en Boot 4 cambiaron los artefactos:

| Boot 3.5 (lo que usamos) | Boot 4 (lo que te da el Initializr hoy) |
| :--- | :--- |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `flyway-core` + `flyway-database-postgresql` | `spring-boot-starter-flyway` |
| `spring-boot-starter-test` (uno solo) | un `*-test` por starter |

Del Initializr se tomó **únicamente el Maven Wrapper** (`mvnw`, `mvnw.cmd`, `.mvn/`).
La decisión de quedarse en 3.5.16 está registrada como desvío **D-07** en la bitácora del
proyecto: migrar a Boot 4 implica renombres de starters y riesgo desconocido en la
integración WebSocket/FreeMarker, a cambio de nada que el proyecto necesite.

---

## Requisitos

- **Java 21** (verificado con Temurin 21.0.6)
- **PostgreSQL 16+** — en el equipo de desarrollo actual corre PostgreSQL 18 local
- **Maven no hace falta**: usá siempre el wrapper (`./mvnw` en bash, `.\mvnw.cmd` en PowerShell)

## Base de datos

```bash
# Windows: psql no está en el PATH
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -c "CREATE DATABASE modelcollab"
```

El esquema lo crea **Flyway** al arrancar (`src/main/resources/db/migration/V1__init.sql`,
copia literal de §13). Hibernate corre con `ddl-auto: validate`, así que nunca toca el
esquema: si una entidad JPA y la migración se desincronizan, la app **no arranca**. Eso es
deliberado — cualquier cambio de esquema va en una migración `V2__*.sql` nueva, jamás
editando `V1__init.sql`.

Credenciales por defecto en `application.yml` (`postgres` / `12345678`), sobreescribibles
con `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`.

## Correr

```bash
./mvnw clean test        # tests (no necesitan base de datos)
./mvnw spring-boot:run   # http://localhost:8080
```

```bash
curl http://localhost:8080/api/v1/health
# {"status":"UP","service":"modelcollab-backend"}
```

## Docker

`Dockerfile` y `docker-compose.yml` están escritos pero **NO verificados**: el equipo de
desarrollo no tiene Docker instalado (al 3/9/2026). Quedan pendientes de prueba para el
despliegue en AWS del 13/09. Hasta entonces, el backend se valida contra el PostgreSQL local.

---

## El modelo canónico está congelado

`metamodel/model/` implementa el JSON Schema de §7 como records inmutables de Java 21. Es
la **fuente única de verdad** compartida por el lienzo web, el backend, los validadores, el
exportador XMI y los generadores de código. Cambiarlo rompe los cuatro a la vez.

`CanonicalModelRoundTripTest` es el test que congela el contrato: lee los tres diagramas de
referencia, los deserializa, los vuelve a serializar y compara los árboles JSON. **Si falla,
el cambio que hiciste rompe a los otros tracks** — no lo "arregles" tocando el JSON de
referencia.

### Reglas del contrato

| Tema | Regla |
| :--- | :--- |
| Defaults de §7 | Se aplican **al deserializar**, en el constructor compacto del record (width 240, height 180, length 255, precision 10, scale 2, `isNavigable` true, `owningSide` SOURCE, `isAbstract`/`isPrimaryKey`/`isUnique` false, `isNullable` true). Un modelo deserializado ya está normalizado: aguas abajo nunca hay nulls en esos campos. |
| Tipos envueltos | `Double`/`Boolean`/`Integer` en vez de primitivos: con primitivos Jackson no puede distinguir "campo ausente" de un `0`/`false` explícito, y los defaults serían inaplicables. |
| Listas | Una lista ausente se normaliza a lista vacía inmutable, nunca a `null`. |
| Un solo ObjectMapper | `config.JacksonConfig.canonicalObjectMapper()` — `@Bean @Primary`, y también estático para que los tests no levanten el contexto. Si REST, STOMP y JSONB usaran mappers distintos, §7 dejaría de estar congelado de hecho aunque lo esté en papel. |
| Campos desconocidos | **Estricto a propósito** (`FAIL_ON_UNKNOWN_PROPERTIES`). Un campo extra da 400 en vez de ignorarse en silencio. Es el único mecanismo que detecta drift en el momento en que ocurre. |
| Opcionales | Se **omiten**, no se mandan como `null` explícito (la serialización usa `NON_NULL`, así que un `null` explícito de entrada se perdería en el round-trip). |
| `visibility` | §7 no define default. Convención: **PUBLIC** en clases y métodos, **PRIVATE** en atributos (campo privado + getter público, que es lo que espera el generador). |
| `owningSide` | "El lado donde vive la FK". Casi siempre **derivable** de las multiplicidades; el campo sólo es autoritativo cuando no hay respuesta mecánica. Ver la tabla de abajo y el javadoc de `Relationship`. |
| `joinTableName` | Sólo en MANY_TO_MANY. Si viene ausente, se deriva como `{source}_{target}` en snake_case. |
| Multiplicidades | Ausentes en INHERITANCE y DEPENDENCY (el canvas no dibuja cardinalidades en una flecha de herencia y el XMI no las espera). |
| `defaultValue` | Siempre string, incluso para no-strings (`"true"`, `"0"`, `"10.00"`). El generador castea según el `AttributeType`. |
| `current_state` | Se guarda como `String` con `@JdbcTypeCode(SqlTypes.JSON)`, no como objeto mapeado por Hibernate: así la única ruta de conversión a `CanonicalModel` pasa por el mapper canónico y Hibernate no puede introducir una serialización paralela. |

### Dónde vive la FK: autoridad de `owningSide`

El generador **no confía ciegamente** en `owningSide`. Un diagrama escrito a mano —o generado
por Gemini en la Fase 4— puede traerlo invertido, o simplemente heredar el default `SOURCE`
del schema sin que nadie lo haya decidido, y eso pondría las FK del lado equivocado en
silencio. Casi siempre el lado dueño es derivable de las multiplicidades:

| Caso | Lado dueño | `owningSide` |
| :--- | :--- | :--- |
| Hay un extremo de colección (`0..*` / `1..*`) | Derivado: el lado "muchos" | Informativo — advertencia si contradice |
| Escalares distintos (`1..1` ↔ `0..1`) | Derivado: el extremo "flojo", el `0..1` | Informativo — advertencia si contradice |
| Escalares iguales (`1..1` ↔ `1..1`, `0..1` ↔ `0..1`) | No derivable | **Autoritativo** — advertencia **siempre** |
| `MANY_TO_MANY` | Elección del modelador (dónde va `@JoinTable`) | **Autoritativo** |
| `INHERITANCE` / `DEPENDENCY` | No aplica | Ignorado |

El caso de los escalares distintos no es una convención arbitraria sino diseño de base de
datos: con la FK en el extremo obligatorio, la columna tendría que ser nullable sobre la
mayoría de las filas más un UNIQUE para simular el 1:1; en el extremo flojo es NOT NULL y no
deja huérfanos. Ejemplo real del fixture: `Cita 1..1 → Pago 0..1` genera `cita_id NOT NULL`
en `pagos`, no `pago_id` en `citas`.

El tercer caso es el único donde el campo decide el esquema sin posibilidad de verificación
—y como el constructor compacto ya normalizó el default, no se puede distinguir "vino
ausente" de "lo eligieron a propósito"—, así que el `MetamodelValidator` debe advertir
**siempre** ahí, aunque el valor sea correcto.

### Nota para el lienzo web

El store del canvas debe mantener el **estado de presentación** (selección, arrastre, hover,
`zIndex` de React Flow) **separado** del estado canónico, y emitir sólo el canónico limpio.
Si algún campo visual necesita persistir de verdad, se agrega a §7 formalmente y se revisan
canvas + generador + XMI a la vez — eso es lo que significa que el contrato esté congelado.

### Diagramas de referencia

`src/test/resources/reference-diagrams/{barberia,veterinaria,inventario}.json` son los datos
de prueba de **todo** el proyecto: canvas, generador de código, XMI y app móvil. Sus UUIDs
son deterministas (uuid5 sobre un namespace fijo), así que se pueden citar por id desde
cualquier track.

| Diagrama | Cubre |
| :--- | :--- |
| `barberia` | MANY_TO_MANY con `joinTableName` explícito, COMPOSITION, waypoints |
| `veterinaria` | INHERITANCE desde una clase abstracta (`Persona`), AGGREGATION, dos paquetes |
| `inventario` | MANY_TO_MANY derivable, multi-paquete (`catalogo` / `operaciones`) |

**Estos tres son los únicos que se demuestran ante el tribunal.** No les agregues casos raros
para probar el generador: para eso están los dos grupos de abajo. Un dominio con una clase
metida a la fuerza para cubrir una rama huele a "preparado para la demo", que es justo lo que
hay que evitar.

### `edge-cases.json` — raro pero válido

Lo que los dominios naturales no producen y el generador igual tiene que soportar. Debe pasar
round-trip y, desde el día 9, generar código que compile.

| Cubre | Dónde |
| :--- | :--- |
| Palabras reservadas de SQL (§11.2.1) | Clases `Order`, `Group`, `Table` |
| Palabras reservadas de Java (§11.2.1) | Atributos `class`, `public`, `int` en `Order` |
| Inyección automática de PK (§11.2.2) | `SinPrimaryKey`, sin ningún `isPrimaryKey: true` |
| Clase completamente vacía | `SinAtributos` |
| `owningSide` caso 3 (simétrica) | `Cuenta 1..1 ↔ Credencial 1..1`, `Suscripcion 0..1 ↔ Cupon 0..1` |
| M2M sin `joinTableName` | `Order ↔ Table` → deriva `order_table` |
| Multiplicidad `1..*` | `Group 1..1 → Order 1..*` |
| `DEPENDENCY` | `Auditoria → Order` |
| Método con parámetros y retorno no primitivo | `Group.buscarMiembros(String, Integer): List<Order>` |

Entre los tres dominios y este archivo se cubren los **9 `AttributeType`** del schema (hay un
test que lo verifica: un tipo sin fixture es una rama del generador que nunca se ejecuta).

### `invalid-cases/` — se rechazan a propósito

Modelos **estructuralmente válidos** según §7 —deserializan sin problema— pero semánticamente
inválidos. El `PreGenerationValidatorService` (día 10) tiene que rechazarlos: acá el test pasa
cuando el modelo **es rechazado**, al revés que en todos los demás.

| Archivo | Violación |
| :--- | :--- |
| `herencia-multiple.json` | `Hijo` con dos relaciones `INHERITANCE` — Java no lo soporta (§11.2.3, error bloqueante) |
| `relacion-huerfana.json` | `sourceClassId` apuntando a un id inexistente: el residuo que deja un `DELETE_CLASS` mal aplicado |

---

## Autenticación y arbitraje de roles (Día 2)

### ⚠️ Los despachos de error deben ir en `permitAll`

`SecurityConfig` excluye `DispatcherType.ERROR` y `ASYNC` de la autorización. **No lo saques.**

Desde Spring Security 6 el filtro de autorización se aplica a *todos* los tipos de despacho, no
sólo a las peticiones del cliente. Cuando un controlador responde un error, Spring lo reenvía
internamente a `/error`; si ese reenvío también se autoriza y `/error` no está permitido, la
respuesta que sale al cliente es un **403 con cuerpo vacío**, sea cual fuere el error real. Se
pierden el 409 de email repetido, el 401 de credenciales, el 400 de validación y el 405 — todos
salen como 403.

Es un fallo silencioso y engañoso: los caminos felices (200/201) funcionan perfecto, así que una
prueba de humo que sólo registre y loguee pasa en verde. Se detectó justamente así, con un
`register` que devolvía 403 en vez de 409. Excluir los despachos internos **no** expone `/error`:
una petición directa a esa ruta sigue siendo un despacho `REQUEST` y sigue protegida.

### Contrato congelado (D-11)

| Qué | Contrato |
| :--- | :--- |
| `POST /api/v1/auth/register` | ← `{email, password, fullName}` · **201** `{token, tokenType, userId, email, fullName}` · email repetido → **409** |
| `POST /api/v1/auth/login` | ← `{email, password}` · **200** mismo cuerpo · credenciales malas → **401** |
| Ruta privada sin token | **401** (no 403: 403 significaría "sé quién sos y no te alcanza") |
| WebSocket | `ws://localhost:8080/ws` — **nativo, sin SockJS** |
| CONNECT | header nativo `Authorization: Bearer <jwt>`; sin token o inválido → se rechaza la conexión |
| Destinos de aplicación | `/app/diagram/{diagramId}/mutate` · `/app/diagram/{diagramId}/lock` (Día 3) |
| Difusión | `/topic/diagrams/{diagramId}` |
| Errores privados | `/user/queue/errors` → `{status, code, destination, clientMutationId, message}` |
| Mutación | `{op, targetId, clientMutationId, payload}` — `op` del catálogo de §8.1 |

Los campos mutables van anidados bajo `payload` y no sueltos en la raíz como los dibuja el boceto
de §8.3: el interceptor tiene que autorizar sin conocer los 22 conjuntos de campos, y
`diagram_operations.payload` de §13 ya es un JSONB que espera ese sub-objeto.

### Dos decisiones del `StompChannelInterceptor`

| Decisión | Por qué |
| :--- | :--- |
| El 403 **descarta el mensaje** (`preSend` devuelve `null`), no lanza | Lanzar emite un frame ERROR y STOMP **cierra la sesión**: un VIEWER que arrastre un nodo sin querer quedaría expulsado de la sala y dejaría de ver los cambios ajenos. Descartando, la conexión sobrevive y el error llega por el canal privado. Un rol de sólo lectura no puede autodestruirse al mirar. |
| El rol se **cachea en la sesión** (`role:{diagramId}`) | `MOVE_CLASS` viaja a ~60 mensajes/s durante un arrastre; con 20 colaboradores serían 1200 consultas por segundo para responder siempre lo mismo. **Consecuencia asumida:** un cambio de rol recién se aplica al reconectar. |

El CONNECT sí lanza: una sesión sin identidad no tiene nada que hacer abierta.

`CollaborationStompController` es **andamio del Día 2**: reemite con `userId` y `serverTimestamp`
pero **no persiste nada**. El Día 3 lo reemplazan `RoomManager`, `SequenceService` y
`LockManagerService`.

---

## Colaboración: locks y persistencia (Día 3)

A partir de acá el servidor **es el dueño del estado**, no un reemisor. Toda mutación se aplica
al `current_state` JSONB, sube `mutation_version` y deja su fila en `diagram_operations`.

### Reglas que no se pueden invertir

| Regla | Por qué |
| :--- | :--- |
| **Primero se persiste, después se difunde** | Al revés, si el guardado falla los clientes quedan pintando un estado que el servidor no tiene, y nadie les va a mandar un mensaje diciendo "aquello que te mostré no pasó". La difusión ocurre fuera de la transacción, ya commiteada. |
| **La idempotencia se comprueba ANTES que el lock** | Un reintento llega con sesión nueva y por lo tanto sin lock, que murió con la anterior. Con el orden inverso, quien reintenta algo **ya aplicado** recibe `LOCK_REQUIRED` y cree que perdió su cambio. Un duplicado no es un intento de mutar. |
| **El contador lo incrementa PostgreSQL** | `UPDATE ... SET n = n + 1 ... RETURNING n`. Leer-y-sumar en Java da números repetidos con dos usuarios, y un `synchronized` deja de valer con dos instancias detrás del balanceador (§5.3). El lock de fila además serializa la mutación entera, no sólo el contador. |
| **Un solo barrido de locks, no un temporizador por lock** | Con 20 personas editando serían decenas de `ScheduledFuture` vivos, cada uno reprogramándose en cada heartbeat. Un `@Scheduled(fixedRate = 1000)` sobre un mapa chico es más barato y mucho más fácil de razonar. |
| **`@EnableScheduling` vive en `SchedulingConfig`** | Olvidarlo es un fallo mudo: los `@Scheduled` no corren, sin error. El síntoma sería candados que nunca se despintan en la pantalla de los demás. |

### Locks (§8.2)

Estado **en memoria**, TTL 5000 ms, heartbeat del cliente cada 2000 ms. No se persisten a
propósito: un lock que sobrevive a un reinicio deja elementos bloqueados por alguien que ya no
está conectado — sería un defecto disfrazado de durabilidad.

**Fallo rápido, sin cola.** Si el recurso está tomado se responde `LOCK_DENIED` en el acto con
`expiresInMs`. Nadie espera a nadie, así que no hay ciclo de espera posible: eso es lo que
garantiza ausencia de interbloqueos e inanición.

Se liberan por cuatro caminos: `RELEASE` explícito, vencimiento del TTL (barrido), confirmación
de la operación que lo exigía (§8.3 paso 8) y desconexión de la sesión.

### Dos canales privados distintos

| Canal | Qué lleva |
| :--- | :--- |
| `/user/queue/errors` | `{status, code, destination, clientMutationId, message}` — rechazos: `FORBIDDEN_ROLE`, `LOCK_REQUIRED`, `TARGET_NOT_FOUND`, `INVALID_PAYLOAD`, `DUPLICATE_ID`, `UNKNOWN_OP`, `OP_NOT_IMPLEMENTED` |
| `/user/queue/locks` | `{event:"LOCK_DENIED", targetId, heldBy, expiresInMs}` |

**No se unifican a propósito.** Que un elemento esté tomado es operación normal y el cliente lo
muestra como un aviso discreto; si viniera por el canal de errores, la interfaz lo trataría como
un fallo y le enseñaría al usuario a desconfiar de una herramienta que funciona bien.

### Difusión a `/topic/diagrams/{id}`

Mutaciones: `{op, targetId, clientMutationId, payload, userId, serverTimestamp, sequenceNum}`.
Eventos de lock: `{event, targetId, userId, userName, color, ttl}` y `{event, targetId}` para
`LOCK_RELEASED`.

`userId` y `serverTimestamp` salen del servidor y nunca del cuerpo del mensaje: aceptarlos del
cliente permitiría firmar una mutación con el id de otro. `sequenceNum` es lo que le deja al
cliente detectar que se perdió algo y lo que va a pedir el rebase offline del Día 10.

### Qué operación exige lock

Lo decide `collaboration/dto/OperationType`, **única fuente de verdad**, no un `if` repartido por
los servicios. Las 22 operaciones de §8.1 con su política, y una marca de cuáles aplica hoy el
`CanonicalMutationApplier`: `UPDATE_RELATIONSHIP` es del Día 12 y `BULK_MERGE` del Día 16, y
hasta entonces se rechazan con `OP_NOT_IMPLEMENTED` en vez de aceptarse y perderse en silencio.

`DELETE_CLASS` limpia las relaciones huérfanas en cascada. Sin eso el servidor fabricaría sus
propios `invalid-cases/relacion-huerfana.json`.

---

## Modo offline: rebase y reconciliación (§9.2)

`POST /api/v1/diagrams/{id}/sync-offline` recibe la cola que el cliente acumuló sin conexión,
**en orden**, y devuelve qué se aplicó, qué se descartó y por qué, más el estado reconciliado.

### Acá se aplican operaciones "que requieren lock" sin lock, a propósito

Leído sin contexto parece un agujero; es lo contrario. §8.4: un cliente sin conexión **no puede
adquirir locks** porque el árbitro es el servidor, así que edita optimistamente sobre su copia
local. La exclusión mutua no desaparece — **se traslada del momento de la edición al momento del
rebase**. El lock es *prevención* y necesita árbitro en línea; el rebase es *detección y
resolución* y funciona sin él. Por eso `OfflineReconciliationService` no consulta el
`LockManagerService`: las reglas de §9.2 hacen de exclusión mutua ahí.

### Políticas por tipo de operación

| Operación | Política |
| :--- | :--- |
| `MOVE_CLASS` | Idempotente: se aplican las últimas coordenadas |
| `ADD_CLASS` / `ADD_RELATIONSHIP` / `ADD_PACKAGE` | Se descarta si el identificador ya existe |
| `ADD_ATTRIBUTE` / `ADD_METHOD` | Sólo si la clase contenedora **sigue existiendo** |
| `UPDATE_*` / `RENAME_*` | Se descarta si el objetivo fue eliminado durante la desconexión |
| `DELETE_*` | **Gana siempre**, y arrastra las relaciones asociadas |

El `clientTimestamp` **no es autoritativo**: sólo preserva el orden relativo de la cola. El sello
oficial lo pone el servidor. Si se respetara el del cliente, alguien con el reloj adelantado
podría insertar operaciones "antes" de cambios ajenos ya aplicados.

### El reporte nombra las cosas, no las numera

```
ADD_ATTRIBUTE 'telefono' descartado: la clase 'Cliente' fue eliminada por otro colaborador.
```

Ese texto se le muestra al usuario, así que decir *"descartada la operación c7f3a1b2"* no sirve.
La parte difícil es que **para cuando se arma el mensaje, la clase ya no existe** — se descartó
justamente porque otro la borró. El nombre sale de la bitácora, y por eso:

> **Un `DELETE_*` guarda en `diagram_operations` el nombre de lo que borró**, tomado del estado
> *anterior* a aplicarlo. Es el único instante en que ese dato existe. Sin eso, el historial de
> un borrado es un UUID sin significado y el reporte no puede explicar nada.

El `ResolutorDeNombres` se construye **sólo si hay algo que descartar**: en el camino feliz no se
lee la bitácora.

### La migración `V2`

| Cambio | Motivo |
| :--- | :--- |
| `UNIQUE (diagram_id, client_mutation_id)` | Con el rebase las operaciones llegan **en lote** y dos reintentos pueden solaparse. Un chequeo leído-antes-de-escribir no es atómico; una restricción sí. Va sobre el **par** porque el `clientMutationId` identifica una operación dentro de *un* diagrama — global, la búsqueda de duplicados estallaba si dos diagramas repetían un identificador. |
| `target_id` en `diagram_operations` | §13 no lo define, y sin él la bitácora **no se puede reproducir**: el payload de un `RENAME_CLASS` es `{"name": …}` y no dice qué clase. Deja el log como auditoría parcial en vez de historial. |

`V1__init.sql` no se toca nunca: todo cambio de esquema va en una migración nueva.

### Un solo evento a la sala

Terminado el rebase se difunde **un** `OFFLINE_SYNC` con el estado completo, no las N operaciones
de la cola: reemitirlas una por una haría parpadear N veces el lienzo de todos y los pasearía por
estados intermedios que nunca existieron. Mismo criterio que §8.1 fija para `BULK_MERGE`.

### `GET /api/v1/diagrams/{id}/snapshot`

Estado canónico actual (§14). Lo lee **cualquier miembro, VIEWER incluido** — mirar es
exactamente lo que un VIEWER sí puede hacer. `sync-offline`, en cambio, exige OWNER o EDITOR: sin
ese control el endpoint sería la puerta de atrás por la que un VIEWER reescribe el diagrama
entero mandando una "cola offline" por REST.

---

## Presencia: roster y cursores

### `USER_CURSOR` tiene destino propio, no viaja por `/mutate`

`/app/diagram/{id}/cursor` ← `{x, y, selectedId}` → difunde a `/topic/diagrams/{id}`
`{event:"USER_CURSOR", userId, userName, color, x, y, selectedId}`.

Tres cosas que **no** hace, y que son la razón de que exista un destino aparte:

| No hace | Por qué |
| :--- | :--- |
| No persiste nada | Es presencia, no historia. Guardar cursores llenaría `diagram_operations` de basura a ~20 mensajes por segundo por usuario. |
| No consume secuencia | Si consumiera, un hueco en la numeración dejaría de significar "me perdí una mutación", que es justo para lo que el cliente la usa al reconectar. |
| No pide lock, y un **VIEWER puede emitirlo** | Mirar dónde apunta quien mira es exactamente lo que un rol de sólo lectura debe poder hacer. La membresía sí se exige: quien no está en el proyecto no entra ni a mirar. |

Hacerlo pasar por la tubería de mutaciones obligaría a poner tres excepciones dentro de ella, y
bastaría olvidar una para ensuciar la bitácora. Es más barato que no entre nunca.

### El roster se difunde entero, y se pide al llegar

`PRESENCE` con la **lista completa** de presentes cada vez que alguien entra o sale. No eventos
incrementales: con "entró fulano / salió mengano", un cliente que se pierde uno queda con un
roster equivocado para siempre y sin forma de notarlo — dibujando el cursor de alguien que ya se
fue. La lista entera cuesta unos bytes más y deja al cliente correcto haya perdido lo que haya
perdido.

> **El recién llegado tiene que pedir el roster:** `/app/diagram/{id}/presence` → responde por
> `/user/queue/presence`. No se le empuja automáticamente al entrar, y no por simplicidad: **se
> intentó y no funciona**. El registro de la suscripción en el broker y el evento que dispara la
> difusión viajan por caminos distintos, así que hay una ventana en la que el servidor ya lo
> cuenta como presente pero el broker todavía no le entrega nada — y su canal privado tiene
> exactamente la misma carrera. La única forma sin condiciones de carrera es que pregunte cuando
> ya terminó de suscribirse a todo. De ahí en más, las altas y bajas ajenas le llegan solas.

Cada participante trae su **color**, el mismo que usan sus locks y su cursor, para que el candado
de una clase, el puntero y la entrada en la lista se reconozcan como la misma persona. El color
se deriva del `userId` y no se reparte por orden de llegada: así no cambia al reconectar.

Dos pestañas del mismo colaborador son dos sesiones pero **una** persona en el roster.

---

## Generador de backend (capas 1-2)

`SpringBootGeneratorService.generar(modelo, paquete, artefacto)` devuelve un mapa
*ruta relativa → contenido*, no escribe en disco. Así el empaquetador ZIP del Día 11 y el test de
compilación usan el mismo código: si el generador escribiera archivos por su cuenta, el test
verificaría algo distinto de lo que el usuario descarga.

### "100% Compila" es un test, no una promesa

`GeneradorCompilaTest` **genera y compila** los tres diagramas de referencia más `edge-cases`. Si
una plantilla produce código inválido, la suite se pone roja. Es el criterio que el plan pide para
el CI del Día 12, construido desde el primer día para no llegar allá con plantillas revisadas a
ojo.

Compila con el `JavaCompiler` del JDK en vez de invocar Maven: no necesita red, tarda un segundo
en vez de un minuto, y el classpath que le pasa es el de este proyecto, que ya trae JPA y Spring
Data — o sea justo lo que declara el `pom.xml` generado.

### El proyecto generado trae su propio Maven Wrapper

`mvnw`, `mvnw.cmd` y `.mvn/wrapper/`. El criterio del Día 11 es *"descargar el ZIP y compilarlo
con `mvn clean compile`"*, y eso da por sentado que quien lo recibe tiene Maven instalado — cosa
que no es cierta ni en la máquina donde se desarrolla esto. Verificado: `./mvnw clean compile`
sobre el proyecto de la barbería termina en 0 y produce los `.class`.

Los archivos del wrapper se copian **sin pasar por FreeMarker**: son scripts llenos de `${...}`
propios que la plantilla intentaría interpolar.

### Las dos listas de palabras reservadas (§11.2.1)

| Identificador | En Java | En SQL | Qué hace el generador |
| :--- | :--- | :--- | :--- |
| `Order`, `Group`, `Table` | válidos | **reservados** | La clase conserva su nombre; la tabla va escapada: `@Table(name = "\"order\"")` |
| `class`, `public` | **reservados** | válidos | El campo pasa a `_class`; la columna conserva el nombre: `@Column(name = "class")` |
| `int` | **reservado** | **reservado** | Cae en las dos: `private Integer _int;` con `@Column(name = "\"int\"")` |

Son conjuntos distintos aplicados en capas distintas. Con una sola lista se rompe por los dos
lados: o se rechazan nombres de clase legales, o se genera DDL que no se puede ejecutar.

### ⚠️ La trampa del import que ensombrece

`edge-cases.json` tiene una clase llamada **`Table`**, y eso no lo arregla ninguna lista de
palabras reservadas:

- Con `import jakarta.persistence.Table;`, ese import **ensombrece** a la entidad `Table` del
  mismo paquete (JLS 6.4.1) y la relación `Order`–`Table` deja de compilar.
- Sin ese import, el `@Table` de la entidad se resuelve contra la clase del diagrama y tampoco
  compila.

**No hay un orden de imports que salve los dos casos.** La salida es calcular las colisiones
contra los nombres de clase del diagrama y usar el nombre calificado sólo para las que chocan:
`@jakarta.persistence.Table(...)` en ese archivo, `@Table(...)` en todos los demás. Vale igual
para `List`, `UUID` o `String` — nada impide que alguien dibuje una clase así.

### Otras decisiones

| Decisión | Motivo |
| :--- | :--- |
| Toda la lógica en `ModeloDeGeneracion`, ninguna en las plantillas | Un `.ftl` no compila, no tiene tipos y falla recién al renderizar. Las plantillas sólo interpolan cadenas ya resueltas. |
| Los dos lados de una relación salen de un único cálculo | El `mappedBy` tiene que nombrar un campo que exista. Escritos por separado se desincronizan, y eso Hibernate lo rechaza **al arrancar**, no al compilar. |
| La subclase no redeclara `@Id` | Lo hereda. Redeclararlo es un error de arranque, no de compilación: se escaparía del test. |
| Las clases abstractas no llevan repositorio | No se consultan por sí solas. Sus subclases sí, con el tipo de id del padre. |
| La estrategia de `@GeneratedValue` sigue al tipo de la PK | Una PK `UUID` con `IDENTITY` no arranca, y una `Long` con `GenerationType.UUID` tampoco. |
| `COMPOSITION` propaga el borrado | Las partes no viven sin el todo. Sin `orphanRemoval`, borrar una `Cita` deja sus `Pago` huérfanos. |

## Estructura

Los paquetes de §6 existen desde el día 1 (los vacíos llevan `.gitkeep`) para evitar
refactors de imports más adelante. **No se usa Lombok**: el modelo canónico va con records
de Java 21.
