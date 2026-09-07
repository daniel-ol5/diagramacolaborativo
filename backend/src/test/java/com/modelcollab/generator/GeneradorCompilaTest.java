package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

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
