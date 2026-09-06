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

## Estructura

Los paquetes de §6 existen desde el día 1 (los vacíos llevan `.gitkeep`) para evitar
refactors de imports más adelante. **No se usa Lombok**: el modelo canónico va con records
de Java 21.
