# ${artefacto}

Backend Spring Boot 3 generado por **ModelCollab** a partir de un diagrama de clases UML.

## Compilar y ejecutar

El proyecto trae su propio **Maven Wrapper**, así que no hace falta instalar Maven:

```bash
./mvnw clean compile       # Linux / macOS
.\mvnw.cmd clean compile   # Windows
```

```bash
./mvnw spring-boot:run     # http://localhost:8080
```

## Base de datos

PostgreSQL en `localhost:5432/${artefacto}`. Se puede apuntar a otra sin recompilar:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://mi-servidor:5432/mibase \
SPRING_DATASOURCE_USERNAME=usuario \
SPRING_DATASOURCE_PASSWORD=clave \
./mvnw spring-boot:run
```

Hibernate arranca con `ddl-auto: update`, o sea que crea las tablas a partir de las entidades la
primera vez. Para producción conviene pasarlo a `validate` y versionar el esquema con
migraciones.

## Qué hay acá

| Capa | Paquete |
| :--- | :--- |
| Entidades JPA | `${paquete}.domain.model` |
| Repositorios | `${paquete}.repository` |

## Sobre los nombres

Los identificadores del diagrama se sanean en **dos capas independientes**:

- Una clase llamada `Order` o `Table` conserva su nombre en Java y su tabla va escapada
  (`@Table(name = "\"order\"")`), porque son palabras reservadas de SQL pero no de Java.
- Un atributo llamado `class` o `int` se convierte en el campo `_class` o `_int` y conserva su
  nombre real de columna con `@Column`, porque son reservadas de Java pero no de SQL.

Si una clase no declaraba clave primaria, el generador le inyectó un `Long id` con
`GenerationType.IDENTITY`.

**No edites los archivos generados a mano:** se sobrescriben al regenerar desde el diagrama.
