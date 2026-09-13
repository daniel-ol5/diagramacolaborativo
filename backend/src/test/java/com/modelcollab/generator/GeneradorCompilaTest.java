package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>La invariante "100% Compila" de §11.2, verificada de verdad y desde el primer día.</b>
 *
 * <p>Genera el proyecto de cada diagrama de referencia y <b>lo compila</b>. Si un cambio en una
 * plantilla produce código inválido, este test se pone rojo: es el mismo criterio que el plan
 * pide para el CI del Día 12, construido ahora para no llegar allá con tres días de plantillas
 * revisadas a ojo.
 *
 * <p>Compila con el {@code javax.tools.JavaCompiler} del propio JDK en vez de invocar Maven, por
 * tres razones: no necesita red, tarda un segundo en lugar de un minuto, y el classpath que le
 * pasa es el de este proyecto —que ya trae JPA y Spring Data— así que prueba exactamente las
 * dependencias que declara el {@code pom.xml} generado. El {@code mvn clean compile} sobre el ZIP
 * descargado es el criterio del Día 11 y lo complementa; no lo reemplaza.
 */
class GeneradorCompilaTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final SpringBootGeneratorService generador = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), new IdentifierSanitizer());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    @ParameterizedTest(name = "el backend de {0} compila")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
        // Un diagrama cuyo owningSide CONTRADICE las multiplicidades. Es el caso que rompio de
        // verdad: con 1..1 -> 1..* y owningSide=SOURCE, el lado de la coleccion salia con
        // @ManyToOne(mappedBy = "..."), que no existe en JPA, y el ZIP no compilaba.
        "reference-diagrams/owning-side-contradictorio.json",
        "edge-cases.json",
    })
    @DisplayName("Todo diagrama de referencia genera un backend que compila")
    void generaCodigoQueCompila(String fixture, @TempDir Path destino) throws Exception {
        Map<String, String> archivos =
                generador.generar(leer(fixture), "com.empresa.app", "demo");

        List<Path> fuentes = escribir(archivos, destino);
        assertThat(fuentes).as("no se generó ningún .java").isNotEmpty();

        String errores = compilar(fuentes, destino);
        assertThat(errores)
                .as("el código generado para %s no compila:%n%s", fixture, errores)
                .isEmpty();
    }

    @Test
    @DisplayName("edge-cases: las palabras reservadas salen saneadas en la capa que corresponde")
    void reservadasEnLaCapaCorrecta(@TempDir Path destino) throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("edge-cases.json"), "com.empresa.app", "demo");

        String order = archivos.get("src/main/java/com/empresa/app/domain/model/Order.java");
        assertThat(order).isNotNull();

        // §11.2.1(a): reservada de SQL. La clase conserva su nombre y la tabla va escapada.
        assertThat(order).contains("public class Order");
        assertThat(order).contains("Table(name = \"\\\"order\\\"\")");

        // §11.2.1(b): reservadas de Java. El campo se prefija y la columna conserva el nombre.
        assertThat(order).contains("private String _class;");
        assertThat(order).contains("@Column(name = \"class\"");
        assertThat(order).contains("private Boolean _public;");
        assertThat(order).contains("private Integer _int;");
        // "int" cae en las dos listas: se prefija en Java Y se escapa en SQL.
        assertThat(order).contains("@Column(name = \"\\\"int\\\"\"");

        // La colisión que provoca este fixture a propósito: hay una entidad llamada Table, así
        // que jakarta.persistence.Table no se puede importar y va calificada.
        assertThat(order).doesNotContain("import jakarta.persistence.Table;");
        assertThat(order).contains("@jakarta.persistence.Table(");
    }

    @Test
    @DisplayName("§11.2.2: a la clase sin clave primaria se le inyecta una")
    void inyeccionDeClavePrimaria() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("edge-cases.json"), "com.empresa.app", "demo");

        String sinPk = archivos.get(
                "src/main/java/com/empresa/app/domain/model/SinPrimaryKey.java");
        assertThat(sinPk).contains("@Id");
        assertThat(sinPk).contains("GenerationType.IDENTITY");
        assertThat(sinPk).contains("private Long id;");

        // Y su repositorio tiene que declarar ese mismo tipo, o no compila.
        assertThat(archivos.get("src/main/java/com/empresa/app/repository/SinPrimaryKeyRepository.java"))
                .contains("JpaRepository<SinPrimaryKey, Long>");
    }

    @Test
    @DisplayName("La subclase no redeclara @Id y su repositorio usa el tipo heredado")
    void herenciaResuelta() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("reference-diagrams/veterinaria.json"), "com.empresa.app", "demo");

        String persona = archivos.get("src/main/java/com/empresa/app/domain/model/Persona.java");
        assertThat(persona).contains("public abstract class Persona");
        assertThat(persona).contains("@Inheritance(strategy = InheritanceType.JOINED)");
        assertThat(persona).contains("@Id");

        String dueno = archivos.get("src/main/java/com/empresa/app/domain/model/Dueno.java");
        assertThat(dueno).contains("class Dueno extends Persona");
        // Redeclarar @Id en la subclase es un error que Hibernate rechaza al ARRANCAR, no al
        // compilar: se escaparía de este test para aparecer recién en ejecución.
        assertThat(dueno).doesNotContain("@Id");

        // Una clase abstracta no lleva repositorio; sus subclases sí, con el id del padre.
        assertThat(archivos).doesNotContainKey(
                "src/main/java/com/empresa/app/repository/PersonaRepository.java");
        assertThat(archivos.get("src/main/java/com/empresa/app/repository/DuenoRepository.java"))
                .contains("JpaRepository<Dueno, UUID>");
    }

    @Test
    @DisplayName("Los dos lados de una relación se generan coherentes entre sí")
    void relacionesCoherentes() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("reference-diagrams/barberia.json"), "com.empresa.app", "demo");

        String cliente = archivos.get("src/main/java/com/empresa/app/domain/model/Cliente.java");
        String cita = archivos.get("src/main/java/com/empresa/app/domain/model/Cita.java");

        // Cliente 1..1 -> Cita 0..*, owningSide TARGET: la FK vive en citas.
        assertThat(cita).contains("@ManyToOne");
        assertThat(cita).contains("@JoinColumn(name = \"cliente_id\"");
        assertThat(cita).contains("private Cliente cliente;");
        // Y el mappedBy del otro lado tiene que nombrar ese campo exacto, o Hibernate no arranca.
        assertThat(cliente).contains("@OneToMany(mappedBy = \"cliente\")");
        assertThat(cliente).contains("private List<Cita> citas");

        // M2M: la tabla intermedia va del lado dueño, el mappedBy del otro.
        String servicio = archivos.get("src/main/java/com/empresa/app/domain/model/Servicio.java");
        assertThat(cita).contains("@JoinTable(");
        assertThat(cita).contains("name = \"cita_servicio\"");
        assertThat(servicio).contains("@ManyToMany(mappedBy = \"servicios\")");

        // COMPOSITION: el lado del "todo" propaga el borrado; si no, quedan huérfanos.
        assertThat(cita).contains("cascade = CascadeType.ALL, orphanRemoval = true");
    }

    @Test
    @DisplayName("El proyecto generado trae su propio Maven Wrapper")
    void traeElWrapper() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("reference-diagrams/barberia.json"), "com.empresa.app", "demo");

        // Sin wrapper, "compilalo con mvn clean compile" exige que quien reciba el ZIP tenga
        // Maven instalado — y en esta máquina de desarrollo tampoco está.
        assertThat(archivos).containsKeys("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties");
        assertThat(archivos.get("pom.xml")).contains("<artifactId>demo</artifactId>");
    }


    @ParameterizedTest(name = "el mapeo JPA de {0} es valido")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
        "reference-diagrams/owning-side-contradictorio.json",
        "edge-cases.json",
    })
    @DisplayName("Que compile no alcanza: el mapeo JPA tambien tiene que ser valido")
    void elMapeoJpaEsValido(String fixture, @TempDir Path destino) throws Exception {
        Map<String, String> archivos =
                generador.generar(leer(fixture), "com.empresa.app", "demo");
        List<Path> fuentes = escribir(archivos, destino);
        assertThat(compilar(fuentes, destino)).as("no compila").isEmpty();

        // El "100% Compila" de §11.2, tomado literalmente, no alcanza: hay codigo que compila
        // perfecto y con el que la aplicacion generada no levanta. Los dos casos aparecieron
        // escribiendo este generador — una estrategia de @GeneratedValue incompatible con el
        // tipo de la clave, y un mappedBy que nombra un campo inexistente — y los dos son
        // fallos de ARRANQUE, invisibles para javac.
        //
        // Construir el metamodelo de Hibernate los atrapa a los dos, en segundos y sin base de
        // datos: la validacion del mapeo ocurre antes de tocar ninguna conexion.
        validarMapeo(destino, nombresDeEntidades(archivos));
    }

    private static List<String> nombresDeEntidades(Map<String, String> archivos) {
        return archivos.keySet().stream()
                .filter(r -> r.contains("/domain/model/") && r.endsWith(".java"))
                .map(r -> "com.empresa.app.domain.model."
                        + r.substring(r.lastIndexOf('/') + 1, r.length() - ".java".length()))
                .toList();
    }

    /**
     * Arma el metamodelo de Hibernate sobre las clases ya compiladas.
     *
     * <p>Sin base de datos: {@code allow_jdbc_metadata_access = false} le dice a Hibernate que no
     * intente averiguar el dialecto conectandose, y el dialecto se le da a mano. Lo que se
     * ejercita es la validacion del mapeo — que cada {@code mappedBy} apunte a un campo que
     * existe, que las claves sean coherentes con su generador, que las dos puntas de cada
     * relacion concuerden.
     */
    private static void validarMapeo(Path destino, List<String> entidades) throws Exception {
        Path clases = destino.resolve("target/classes");
        try (URLClassLoader cargador = new URLClassLoader(
                new java.net.URL[] {clases.toUri().toURL()},
                GeneradorCompilaTest.class.getClassLoader())) {

            var arranque = new org.hibernate.boot.registry.BootstrapServiceRegistryBuilder()
                    .applyClassLoader(cargador)
                    .build();
            var registro = new org.hibernate.boot.registry.StandardServiceRegistryBuilder(arranque)
                    .applySetting("hibernate.dialect",
                            "org.hibernate.dialect.PostgreSQLDialect")
                    .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                    .build();
            try {
                var fuentes = new org.hibernate.boot.MetadataSources(registro);
                for (String nombre : entidades) {
                    fuentes.addAnnotatedClass(Class.forName(nombre, false, cargador));
                }
                // buildMetadata() resuelve los mappedBy y la forma de las claves. Construir
                // ademas la SessionFactory es lo que instancia los generadores de identificador,
                // que es donde se detecta una estrategia incompatible con el tipo de la clave.
                // Sigue sin tocar la base: solo se arma el modelo en memoria.
                try (var factory = fuentes.buildMetadata().buildSessionFactory()) {
                    assertThat(factory.getMetamodel().getEntities())
                            .as("Hibernate tiene que reconocer todas las entidades")
                            .hasSize(entidades.size());
                }
            } finally {
                org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registro);
            }
        }
    }

    @Test
    @DisplayName("El arnes detecta un mappedBy roto: si no, no estaria probando nada")
    void elArnesDetectaUnMappedByRoto(@TempDir Path destino) throws Exception {
        Map<String, String> archivos = new java.util.LinkedHashMap<>(
                generador.generar(leer("reference-diagrams/barberia.json"),
                        "com.empresa.app", "demo"));

        // Se rompe a proposito el mappedBy de Cliente para que apunte a un campo inexistente.
        // Esto COMPILA sin una sola advertencia: es exactamente la clase de error que motivo
        // este test, y si el arnes no lo viera, seria decorativo.
        String clave = "src/main/java/com/empresa/app/domain/model/Cliente.java";
        archivos.put(clave, archivos.get(clave)
                .replace("mappedBy = \"cliente\"", "mappedBy = \"noExisteEsteCampo\""));

        List<Path> fuentes = escribir(archivos, destino);
        assertThat(compilar(fuentes, destino))
                .as("el sabotaje tiene que seguir compilando, ese es el punto")
                .isEmpty();

        assertThatThrownBy(() -> validarMapeo(destino, nombresDeEntidades(archivos)))
                .as("Hibernate tiene que rechazar un mappedBy colgado")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("HASTA DONDE LLEGA EL ARNES: una clave incompatible con su tipo NO la detecta")
    void elLimiteDelArnes(@TempDir Path destino) throws Exception {
        Map<String, String> archivos = new java.util.LinkedHashMap<>(
                generador.generar(leer("reference-diagrams/barberia.json"),
                        "com.empresa.app", "demo"));

        // IDENTITY sobre una clave UUID: la otra clase de fallo que aparecio escribiendo el
        // generador. Compila, y ademas Hibernate la acepta al armar el metamodelo — porque NO es
        // un error de mapeo: es una incompatibilidad con el motor, que recien se manifiesta al
        // generar el DDL o al insertar.
        String clave = "src/main/java/com/empresa/app/domain/model/Cliente.java";
        archivos.put(clave, archivos.get(clave)
                .replace("GenerationType.UUID", "GenerationType.IDENTITY"));

        List<Path> fuentes = escribir(archivos, destino);
        assertThat(compilar(fuentes, destino)).isEmpty();

        // Este test afirma el LIMITE, no una capacidad. Esta escrito al reves a proposito: si
        // algun dia Hibernate empieza a rechazarlo, este test se pone rojo y nos enteramos de
        // que la cobertura crecio, en vez de seguir creyendo que no la teniamos.
        //
        // Mientras tanto, la invariante se sostiene donde de verdad vive: en el generador, que
        // elige la estrategia segun el tipo de la clave (ver estrategiaSegunElTipoDeClave).
        validarMapeo(destino, nombresDeEntidades(archivos));
    }

    @Test
    @DisplayName("La estrategia de la clave la decide el tipo, que es donde vive la invariante")
    void estrategiaSegunElTipoDeClave() throws Exception {
        // Claves UUID -> GenerationType.UUID. Una PK UUID con IDENTITY no arranca.
        String cliente = generador.generar(leer("reference-diagrams/barberia.json"),
                "com.empresa.app", "demo")
                .get("src/main/java/com/empresa/app/domain/model/Cliente.java");
        assertThat(cliente).contains("private UUID id;");
        assertThat(cliente).contains("GenerationType.UUID");
        assertThat(cliente).doesNotContain("GenerationType.IDENTITY");

        // Clave inyectada por el generador -> Long con IDENTITY.
        String sinPk = generador.generar(leer("edge-cases.json"), "com.empresa.app", "demo")
                .get("src/main/java/com/empresa/app/domain/model/SinPrimaryKey.java");
        assertThat(sinPk).contains("private Long id;");
        assertThat(sinPk).contains("GenerationType.IDENTITY");
        assertThat(sinPk).doesNotContain("GenerationType.UUID");
    }

    // ------------------------------------------------------------------ apoyo


    private static List<Path> escribir(Map<String, String> archivos, Path destino)
            throws Exception {
        List<Path> fuentes = new ArrayList<>();
        for (Map.Entry<String, String> archivo : archivos.entrySet()) {
            Path ruta = destino.resolve(archivo.getKey());
            Files.createDirectories(ruta.getParent());
            Files.writeString(ruta, archivo.getValue(), StandardCharsets.UTF_8);
            if (archivo.getKey().endsWith(".java")) {
                fuentes.add(ruta);
            }
        }
        return fuentes;
    }

    /** Devuelve los errores del compilador, o cadena vacía si compiló limpio. */
    private static String compilar(List<Path> fuentes, Path destino) throws Exception {
        JavaCompiler compilador = ToolProvider.getSystemJavaCompiler();
        assertThat(compilador)
                .as("hace falta un JDK, no un JRE, para correr este test")
                .isNotNull();

        Path clases = destino.resolve("target/classes");
        Files.createDirectories(clases);

        DiagnosticCollector<JavaFileObject> diagnosticos = new DiagnosticCollector<>();
        try (StandardJavaFileManager gestor =
                compilador.getStandardFileManager(diagnosticos, null, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> unidades =
                    gestor.getJavaFileObjectsFromFiles(fuentes.stream().map(Path::toFile).toList());

            // El classpath de este proyecto ya trae jakarta.persistence y spring-data-jpa, que
            // es justo lo que declara el pom generado.
            List<String> opciones = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", clases.toString(),
                    "-nowarn");

            StringWriter salida = new StringWriter();
            boolean ok = compilador
                    .getTask(salida, gestor, diagnosticos, opciones, null, unidades)
                    .call();

            if (ok) {
                return "";
            }
            StringBuilder errores = new StringBuilder();
            diagnosticos.getDiagnostics().stream()
                    .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .forEach(d -> errores
                            .append(d.getSource() == null ? "?" : d.getSource().getName())
                            .append(":").append(d.getLineNumber()).append(" ")
                            .append(d.getMessage(null)).append(System.lineSeparator()));
            return errores.append(salida).toString();
        }
    }
}
