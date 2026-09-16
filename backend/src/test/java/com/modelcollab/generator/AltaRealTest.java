package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>El backend generado se levanta contra un PostgreSQL de verdad y acepta un alta.</b>
 *
 * <h2>Qué agrega sobre los tres arneses que ya había</h2>
 *
 * <p>Los otros dicen que el código generado <i>compila</i>, que su <i>mapeo JPA es válido</i> y
 * que toda columna obligatoria <i>tiene por dónde llegar</i>. Los tres son estáticos, y los tres
 * daban verde sobre un defecto que hacía imposible crear un {@code Dueno}: el DTO no llevaba los
 * atributos heredados, y el insert violaba el NOT NULL.
 *
 * <p>Eso <b>compilaba</b>. Es una clase entera de defecto que sólo aparece contra una base real,
 * en el primer {@code POST}. El guardián estático tapa la instancia que encontramos; esto tapa la
 * familia.
 *
 * <h2>Los cuatro diagramas, y qué puede romper cada uno</h2>
 *
 * <p>El orden no es alfabético ni histórico: <b>primero el que puede morir donde ningún arnés
 * estático mira</b>.
 *
 * <ul>
 *   <li><b>{@code edge-cases}</b> — sus clases se llaman {@code Order}, {@code Group} y
 *       {@code Table}, que son <b>palabras reservadas de SQL</b>. Si el DDL no las entrecomilla,
 *       {@code CREATE TABLE order (...)} falla contra PostgreSQL y no falla en ningún otro lado:
 *       el Java compila, el mapeo de Hibernate es válido y el guardián de arriba lo aprueba.
 *       Trae además una clase <b>sin clave primaria</b>, donde el generador inyecta una — acá se
 *       ve si la inyectada funciona de verdad o sólo existe en el código.</li>
 *   <li><b>{@code veterinaria}</b> — <b>herencia y una clase abstracta</b>, o sea el diagrama que
 *       ejercita el defecto recién arreglado. Que un {@code Dueno} —cuyos nombres y apellidos
 *       viven en {@code Persona}— se cree de verdad convierte ese arreglo de "pasa un test
 *       estático" a "probado contra la base".</li>
 *   <li><b>{@code barberia}</b> — muchos-a-muchos con tabla intermedia, y una composición.</li>
 *   <li><b>{@code inventario}</b> — dos paquetes y una cadena de referencias más larga, que es
 *       lo que obliga al orden topológico de abajo a ser correcto de verdad.</li>
 * </ul>
 *
 * <h2>Manda sólo lo obligatorio, y eso es deliberado</h2>
 *
 * <p>El cuerpo de cada alta lleva <b>exactamente los campos que el guardián declara obligatorios,
 * y ninguno más</b>. Si el alta pasa, no sólo se probó que la entidad se crea: se validó contra la
 * realidad <b>la regla del guardián</b>. Mandar todos los campos probaría menos, porque un
 * obligatorio que el guardián no viera vendría igual de contrabando.
 *
 * <h2>Opt-in, y por qué</h2>
 *
 * <p>Cuesta unos 25 segundos por diagrama —empaquetar con Maven, arrancar Spring Boot y sembrar—,
 * o sea cerca de dos minutos los cuatro. Metido en la suite de todos los días la llevaría de dos
 * minutos a cuatro, y una suite que tarda el doble es una suite que la gente deja de correr. Se
 * activa con {@code -Dalta-real=true} y en CI, donde lento no molesta.
 */
@EnabledIfSystemProperty(
        named = "alta-real",
        matches = "true",
        disabledReason = "SIN VERIFICAR contra una base real. Se activa con -Dalta-real=true")
class AltaRealTest {

    private static final String HOST = "jdbc:postgresql://localhost:5432/";
    private static final String USUARIO = "postgres";
    private static final String CLAVE = "12345678";

    /** Un puerto que no es el 8080, para no chocar con el backend de ModelCollab. */
    private static final int PUERTO = 8099;

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), sanitizer);

    /** Un campo del RequestDTO: su tipo Java y, si es una referencia, a qué entidad apunta. */
    private record Campo(
            String nombre, String tipo, boolean obligatorio, String referencia, Integer maximo) {}

    /**
     * Cuenta las filas creadas, para que cada texto sea distinto del anterior.
     *
     * <p>Hace falta de verdad: {@code Persona.ci} sale del diagrama con {@code unique = true}, asi
     * que mandar el mismo texto en todas las altas hace que la segunda choque con la primera. La
     * primera version del arnes fallaba por eso — y el 500 que daba no era del generador sino
     * mio. De paso confirma algo bueno: el {@code unique} del diagrama llego hasta la base y se
     * esta aplicando.
     */
    private int contador = 0;

    @ParameterizedTest(name = "{0} se levanta contra PostgreSQL y acepta un alta por entidad")
    @ValueSource(strings = {
        // edge-cases va primero a propósito: es el único que puede morir en el CREATE TABLE, y
        // si algo se va a romper conviene saberlo antes de gastar 75 segundos en los otros tres.
        "edge-cases.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/barberia.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("El backend generado se levanta contra PostgreSQL y acepta un alta por entidad")
    void elDiagramaAceptaAltas(String fixture, @TempDir Path destino) throws Exception {
        String nombre = fixture.substring(fixture.lastIndexOf('/') + 1).replace(".json", "");
        CanonicalModel modelo = leer(fixture);
        Map<String, String> generado = backend.generar(modelo, "com.empresa.app", nombre);
        escribir(generado, destino);

        Map<String, List<Campo>> porEntidad = camposObligatorios(generado);
        Map<String, String> rutas = rutasRest(generado);
        assertThat(porEntidad).as("no se generó ningún DTO").isNotEmpty();

        String base = "alta_real_" + nombre.replace('-', '_') + "_" + System.currentTimeMillis();
        crearBase(base);
        Process servidor = null;
        try {
            empaquetar(destino);
            servidor = arrancar(destino, base);

            Map<String, String> idsCreados = new LinkedHashMap<>();
            for (String entidad : enOrdenDeDependencias(porEntidad)) {
                String id = crear(entidad, rutas.get(entidad),
                        porEntidad.get(entidad), idsCreados);

                assertThat(id)
                        .as("el alta de %s no devolvió un identificador", entidad)
                        .isNotBlank();
                idsCreados.put(entidad, id);
            }

            // Se compara contra EL DIAGRAMA, no contra una lista escrita a mano. En el medio hay
            // dos traducciones —modelo a Java, Java a DTO— y si alguna se come una clase por el
            // camino, el bucle de arriba simplemente no la intenta y el verde no dice nada. Esto
            // es lo que impide que el arnés pase habiendo creado menos de lo que debía.
            assertThat(idsCreados.keySet())
                    .as("no se creó una fila por cada clase concreta de %s", nombre)
                    .containsExactlyInAnyOrderElementsOf(concretas(modelo));

            // Se imprime lo que se creó: un verde no dice QUE probó, y este arnés puede pasar
            // sin haber hecho nada si el parseo devuelve una lista vacía. Con esto, la corrida
            // muestra el alta de cada entidad y el orden en que hubo que hacerlas.
            System.out.println("ALTA REAL contra PostgreSQL — " + nombre + ": "
                    + idsCreados.size() + " entidad(es) creadas en este orden:");
            idsCreados.forEach((entidad, id) ->
                    System.out.println("   " + entidad + " -> " + id));
        } finally {
            if (servidor != null) {
                servidor.destroyForcibly();
                servidor.waitFor(30, TimeUnit.SECONDS);
            }
            borrarBase(base);
        }
    }

    /**
     * Las clases del diagrama que tienen que terminar existiendo como fila.
     *
     * <p>Las abstractas quedan afuera porque no se instancian: {@code Persona} no tiene DTO ni
     * ruta, y exigirla haría fallar a la veterinaria por un motivo inventado.
     */
    private List<String> concretas(CanonicalModel modelo) {
        return modelo.classes().stream()
                .filter(clase -> !Boolean.TRUE.equals(clase.isAbstract()))
                .map(clase -> sanitizer.nombreDeClase(clase.name()))
                .toList();
    }

    // ----------------------------------------------------------- el sembrado

    /**
     * Crea una fila y devuelve su id.
     *
     * <p>Los valores se sintetizan por tipo. Las referencias usan el id de algo <b>ya creado</b>,
     * que es la razón por la que el orden importa.
     */
    private String crear(
            String entidad,
            String ruta,
            List<Campo> campos,
            Map<String, String> idsCreados) throws Exception {

        Map<String, Object> cuerpo = new LinkedHashMap<>();
        for (Campo campo : campos) {
            cuerpo.put(campo.nombre(), campo.referencia() != null
                    ? idsCreados.get(campo.referencia())
                    : valorDe(campo));
        }

        HttpResponse<String> respuesta = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + PUERTO + "/api/" + ruta))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(cuerpo), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(respuesta.statusCode())
                .as("POST /api/%s con %s devolvió %d:%n%s",
                        ruta, cuerpo, respuesta.statusCode(), respuesta.body())
                .isEqualTo(201);

        return mapper.readTree(respuesta.body()).path("id").asText(null);
    }

    /** Un valor plausible para cada tipo del generador, distinto en cada alta. */
    private Object valorDe(Campo campo) {
        int n = ++contador;
        return switch (campo.tipo()) {
            case "Integer", "Long" -> n;
            case "BigDecimal", "Double" -> 1.5;
            case "Boolean" -> true;
            case "LocalDate" -> "2026-01-01";
            case "LocalDateTime" -> "2026-01-01T10:00:00";
            default -> recortar("dato" + n, campo.maximo());
        };
    }

    /** Respeta el {@code @Size(max = N)} que el generador puso desde el diagrama. */
    private static String recortar(String valor, Integer maximo) {
        return maximo != null && valor.length() > maximo
                ? valor.substring(0, maximo)
                : valor;
    }

    /**
     * Las entidades ordenadas de forma que, cuando le toca a una, sus referencias obligatorias ya
     * estén creadas.
     *
     * <p>Sin esto, crear una {@code Consulta} falla porque exige una {@code Mascota} que todavía
     * no existe. Si quedara un ciclo de referencias obligatorias, el diagrama no se podría
     * sembrar de ninguna manera — y eso sería un hallazgo, no un fallo del arnés.
     */
    private static List<String> enOrdenDeDependencias(Map<String, List<Campo>> porEntidad) {
        List<String> orden = new ArrayList<>();
        Set<String> puestas = new LinkedHashSet<>();

        int vueltas = 0;
        while (orden.size() < porEntidad.size() && vueltas++ <= porEntidad.size()) {
            for (Map.Entry<String, List<Campo>> entidad : porEntidad.entrySet()) {
                if (puestas.contains(entidad.getKey())) {
                    continue;
                }
                boolean listo = entidad.getValue().stream()
                        .filter(c -> c.referencia() != null)
                        .allMatch(c -> puestas.contains(c.referencia()));
                if (listo) {
                    orden.add(entidad.getKey());
                    puestas.add(entidad.getKey());
                }
            }
        }
        assertThat(orden)
                .as("hay un ciclo de referencias obligatorias: ese diagrama no se puede sembrar")
                .hasSize(porEntidad.size());
        return orden;
    }

    // ------------------------------------------------- leer el código generado

    private Map<String, List<Campo>> camposObligatorios(Map<String, String> generado) {
        Map<String, Map<String, String>> referenciasPorEntidad = new LinkedHashMap<>();
        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            if (archivo.getKey().contains("/domain/model/")) {
                String clase = nombreDeClase(archivo.getKey());
                referenciasPorEntidad.put(clase, referenciasDe(archivo.getValue()));
            }
        }

        Map<String, List<Campo>> porEntidad = new LinkedHashMap<>();
        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            if (!archivo.getKey().endsWith("RequestDTO.java")) {
                continue;
            }
            String entidad = nombreDeClase(archivo.getKey()).replace("RequestDTO", "");

            // Las referencias pueden venir de un ancestro, igual que los campos.
            Map<String, String> referencias = new LinkedHashMap<>();
            String actual = entidad;
            int guarda = 0;
            while (actual != null && guarda++ < 32) {
                referencias.putAll(referenciasPorEntidad.getOrDefault(actual, Map.of()));
                actual = padreDe(generado, actual);
            }
            porEntidad.put(entidad, camposDelDto(archivo.getValue(), referencias));
        }
        return porEntidad;
    }

    /** Campo del DTO -> entidad referenciada, para los {@code @JoinColumn} obligatorios. */
    private static Map<String, String> referenciasDe(String entidad) {
        Map<String, String> referencias = new LinkedHashMap<>();
        StringBuilder anotaciones = new StringBuilder();

        for (String linea : entidad.split("\n")) {
            String limpia = linea.strip();
            if (limpia.startsWith("@")
                    || (anotaciones.length() > 0 && !limpia.startsWith("private"))) {
                anotaciones.append(' ').append(limpia);
                continue;
            }
            if (!limpia.startsWith("private ")) {
                continue;
            }
            String bloque = anotaciones.toString();
            anotaciones.setLength(0);

            String[] partes = limpia.replace(";", "").split("\\s+");
            if (partes.length >= 3 && bloque.contains("@JoinColumn")
                    && bloque.contains("nullable = false")) {
                referencias.put(partes[2].split("=")[0].strip() + "Id", partes[1]);
            }
        }
        return referencias;
    }

    private static List<Campo> camposDelDto(String fuente, Map<String, String> referencias) {
        List<Campo> campos = new ArrayList<>();
        boolean dentro = false;
        boolean obligatorio = false;
        Integer maximo = null;

        for (String linea : fuente.split("\n")) {
            String limpia = linea.strip();
            if (limpia.startsWith("public record")) {
                dentro = true;
                continue;
            }
            if (!dentro || limpia.startsWith(") {")) {
                continue;
            }
            if (limpia.startsWith("@")) {
                obligatorio |= limpia.contains("NotNull") || limpia.contains("NotBlank");
                // El @Size que el generador puso desde el diagrama. Se respeta al sintetizar el
                // valor: un texto mas largo que el maximo daria un 400 de validacion, y ese 400
                // seria culpa del arnes y no del generador.
                java.util.regex.Matcher tamanio = java.util.regex.Pattern
                        .compile("Size\\(max = (\\d+)\\)").matcher(limpia);
                if (tamanio.find()) {
                    maximo = Integer.valueOf(tamanio.group(1));
                }
                continue;
            }
            String[] partes = limpia.replace(",", "").split("\\s+");
            if (partes.length == 2 && obligatorio) {
                campos.add(new Campo(
                        partes[1], partes[0], true, referencias.get(partes[1]), maximo));
            }
            obligatorio = false;
            maximo = null;
        }
        return campos;
    }

    private static String padreDe(Map<String, String> generado, String clase) {
        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            if (!archivo.getKey().endsWith("/" + clase + ".java")) {
                continue;
            }
            int extiende = archivo.getValue().indexOf(" extends ");
            if (extiende > 0) {
                return archivo.getValue()
                        .substring(extiende + " extends ".length()).split("[\\s{]")[0];
            }
        }
        return null;
    }

    private static Map<String, String> rutasRest(Map<String, String> generado) {
        Map<String, String> rutas = new LinkedHashMap<>();
        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            if (!archivo.getKey().endsWith("Controller.java")) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@RequestMapping\\(\"/api/([a-z0-9-]+)\"\\)")
                    .matcher(archivo.getValue());
            if (m.find()) {
                rutas.put(nombreDeClase(archivo.getKey()).replace("Controller", ""), m.group(1));
            }
        }
        return rutas;
    }

    private static String nombreDeClase(String ruta) {
        return ruta.substring(ruta.lastIndexOf('/') + 1).replace(".java", "");
    }

    // -------------------------------------------------- base, build y arranque

    private static void crearBase(String nombre) throws Exception {
        try (Connection conexion = DriverManager.getConnection(HOST + "postgres", USUARIO, CLAVE);
                Statement sentencia = conexion.createStatement()) {
            sentencia.execute("CREATE DATABASE " + nombre);
        }
    }

    /**
     * Borra la base pase lo que pase.
     *
     * <p>Va en un {@code finally} y se traga los errores a propósito: si el test falla, lo que
     * importa es el fallo, no que además quede ruido. Una base huérfana por corrida en la máquina
     * de desarrollo es exactamente el tipo de basura que hace que nadie quiera correr esto.
     */
    private static void borrarBase(String nombre) {
        try (Connection conexion = DriverManager.getConnection(HOST + "postgres", USUARIO, CLAVE);
                Statement sentencia = conexion.createStatement()) {
            sentencia.execute("DROP DATABASE IF EXISTS " + nombre + " WITH (FORCE)");
        } catch (Exception e) {
            System.err.println("No se pudo borrar la base " + nombre + ": " + e.getMessage());
        }
    }

    private static void empaquetar(Path proyecto) throws Exception {
        Path wrapper = proyecto.resolve("mvnw");
        wrapper.toFile().setExecutable(true);

        // ".\mvnw.cmd" y no "mvnw.cmd": cmd busca en el PATH y el wrapper esta en el
        // directorio del proyecto, no en el PATH. Sin el prefijo dice "no se reconoce como un
        // comando", que suena a que falta Maven cuando en realidad esta ahi al lado.
        Process proceso = new ProcessBuilder(conShell(esWindows() ? ".\\mvnw.cmd" : "./mvnw",
                        "-q", "-B", "package", "-DskipTests"))
                .directory(proyecto.toFile())
                .redirectErrorStream(true)
                .start();

        String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(proceso.waitFor(600, TimeUnit.SECONDS))
                .as("el empaquetado no terminó en 10 minutos").isTrue();
        assertThat(proceso.exitValue())
                .as("el proyecto generado no empaquetó:%n%s", salida).isZero();
    }

    /**
     * Arranca el jar y espera a que <b>ese proceso</b> diga que arrancó.
     *
     * <p>Se espera la línea en SU salida y no que el puerto responda: un puerto ocupado por otro
     * servidor daría verde sin que este proyecto haya llegado a levantar. Ya nos pasó.
     */
    private static Process arrancar(Path proyecto, String base) throws Exception {
        Path jar;
        try (var archivos = Files.list(proyecto.resolve("target"))) {
            jar = archivos.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().endsWith("-plain.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("el empaquetado no dejó ningún jar"));
        }

        ProcessBuilder constructor = new ProcessBuilder(
                "java", "-jar", jar.toAbsolutePath().toString());
        constructor.directory(proyecto.toFile());
        constructor.environment().put("SPRING_DATASOURCE_URL", HOST + base);
        constructor.environment().put("SPRING_DATASOURCE_USERNAME", USUARIO);
        constructor.environment().put("SPRING_DATASOURCE_PASSWORD", CLAVE);
        constructor.environment().put("SERVER_PORT", String.valueOf(PUERTO));
        constructor.redirectErrorStream(true);

        Process proceso = constructor.start();
        StringBuilder salida = new StringBuilder();

        var lector = new java.io.BufferedReader(new java.io.InputStreamReader(
                proceso.getInputStream(), StandardCharsets.UTF_8));
        long limite = System.currentTimeMillis() + 120_000;

        while (System.currentTimeMillis() < limite) {
            String linea = lector.readLine();
            if (linea == null) {
                break;
            }
            salida.append(linea).append('\n');
            if (linea.contains("Started ")) {
                // Se sigue leyendo en otro hilo: si el buffer se llena, el proceso se bloquea.
                Thread drenaje = new Thread(() -> {
                    try {
                        while (lector.readLine() != null) {
                            // se descarta
                        }
                    } catch (Exception ignorado) {
                        // el proceso se cerró
                    }
                });
                drenaje.setDaemon(true);
                drenaje.start();
                return proceso;
            }
        }
        proceso.destroyForcibly();
        throw new AssertionError("el backend generado no arrancó:\n" + salida);
    }

    private static List<String> conShell(String... comando) {
        if (!esWindows()) {
            return List.of(comando);
        }
        List<String> conCmd = new ArrayList<>(List.of("cmd", "/c"));
        conCmd.addAll(List.of(comando));
        return conCmd;
    }

    private static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void escribir(Map<String, String> archivos, Path destino) throws Exception {
        for (Map.Entry<String, String> archivo : archivos.entrySet()) {
            Path ruta = destino.resolve(archivo.getKey());
            Files.createDirectories(ruta.getParent());
            Files.writeString(ruta, archivo.getValue(), StandardCharsets.UTF_8);
        }
    }

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }
}
