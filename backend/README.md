# ModelCollab — Backend

API Spring Boot 3 del editor colaborativo de diagramas UML. La especificación completa
está en `../PLAN_ARQUITECTONICO_FASE_0.txt`; este README cubre sólo cómo levantarlo y las
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
| `owningSide` | "El lado donde vive la FK". En 1..N el generador lo **deriva de las multiplicidades** y el campo es informativo; en MANY_TO_MANY el campo es **autoritativo**. Detalle completo en el javadoc de `Relationship`. |
| `joinTableName` | Sólo en MANY_TO_MANY. Si viene ausente, se deriva como `{source}_{target}` en snake_case. |
| Multiplicidades | Ausentes en INHERITANCE y DEPENDENCY (el canvas no dibuja cardinalidades en una flecha de herencia y el XMI no las espera). |
| `defaultValue` | Siempre string, incluso para no-strings (`"true"`, `"0"`, `"10.00"`). El generador castea según el `AttributeType`. |
| `current_state` | Se guarda como `String` con `@JdbcTypeCode(SqlTypes.JSON)`, no como objeto mapeado por Hibernate: así la única ruta de conversión a `CanonicalModel` pasa por el mapper canónico y Hibernate no puede introducir una serialización paralela. |

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

## Estructura

Los paquetes de §6 existen desde el día 1 (los vacíos llevan `.gitkeep`) para evitar
refactors de imports más adelante. **No se usa Lombok**: el modelo canónico va con records
de Java 21.
