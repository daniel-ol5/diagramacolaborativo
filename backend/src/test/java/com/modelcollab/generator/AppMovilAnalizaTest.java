package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.service.FlutterAppGeneratorService;
import com.modelcollab.generator.service.NluIntentGeneratorService;
import com.modelcollab.generator.service.SpanishNounInflector;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El "100% analiza" de la app móvil: el equivalente del arnés que ya tiene el generador de
 * Spring Boot.
 *
 * <h2>Por qué no alcanza con revisar las plantillas</h2>
 *
 * <p>Ya sabemos lo que pasa cuando el código generado se mira a ojo: en el generador de Spring
 * Boot sobrevivieron <b>tres defectos de imports</b> hasta que hubo un arnés que compilaba de
 * verdad lo que salía. Una plantilla se lee bien y produce código que no compila, porque lo que
 * falla no está en la plantilla sino en la <i>combinación</i> de lo que sale de ella con lo que
 * traía el diagrama.
 *
 * <p>Así que esto genera la app de un diagrama de referencia en un directorio temporal y le
 * corre {@code flutter analyze} encima. No es una comprobación sobre el texto de la plantilla:
 * es el analizador de Dart mirando el proyecto entero.
 *
 * <h2>Qué se considera un fallo</h2>
 *
 * <p>{@code flutter analyze} devuelve <b>1 ante cualquier hallazgo</b>, de cualquier nivel:
 * errores, advertencias y también los {@code info}, entre ellos los avisos de uso de API
 * obsoleta. Sólo devuelve 0 con el proyecto completamente limpio. Medido en esta máquina.
 *
 * <p><b>Corrección de algo que primero di por cierto y no lo era.</b> Al medirlo la primera vez
 * anoté que los {@code info} devolvían 0, y era falso: la prueba que creí que producía un
 * {@code info} no producía ningún hallazgo, y leí "sin salida" como "hay un aviso y pasa". El
 * caso real —tres avisos de obsolescencia en el servicio de voz— devolvió 1. La diferencia entre
 * "no encontré nada" y "encontré algo inofensivo" no se ve en un {@code grep} vacío.
 *
 * <p>Que el listón quede en "cero hallazgos" es más estricto de lo que pensaba y está bien para
 * código generado: <b>nosotros escribimos cada línea</b>, así que un aviso de obsolescencia es un
 * defecto de plantilla y no ruido del analizador. La contrapartida es que una actualización de un
 * paquete que deprecie una API va a poner esto en rojo — y cuando pase, lo que corresponde es
 * actualizar la plantilla, no aflojar la regla.
 *
 * <h2>Si Flutter no está instalado</h2>
 *
 * <p>El test se salta, y no en silencio: deja dicho en el mensaje que la app generada quedó sin
 * verificar. Un arnés que se saltea sin avisar es peor que no tenerlo, porque el verde de la
 * suite pasa a significar algo distinto de lo que parece.
 */
class AppMovilAnalizaTest {

    /** Generoso a propósito: la primera corrida baja las dependencias. */
    private static final int TIMEOUT_SEGUNDOS = 300;

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final FlutterAppGeneratorService generador = new FlutterAppGeneratorService(
            new NluIntentGeneratorService(new SpanishNounInflector()),
            new IdentifierSanitizer(),
            JacksonConfig.canonicalObjectMapper());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    @Test
    @DisplayName("La app generada de la barbería pasa flutter analyze")
    void laAppDeLaBarberiaAnaliza(@TempDir Path destino) throws Exception {
        exigirFlutter();

        Map<String, String> archivos = generador.generar(
                leer("reference-diagrams/barberia.json"), "Barberia Central",
                "https://api.ejemplo.com");

        escribir(archivos, destino);
        Resultado resultado = analizar(destino);

        assertThat(resultado.codigo())
                .as("flutter analyze rechazó la app generada:%n%s", resultado.salida())
                .isZero();
    }

    @Test
    @DisplayName("Y la de la veterinaria también: otro dominio, mismas plantillas")
    void laAppDeLaVeterinariaAnaliza(@TempDir Path destino) throws Exception {
        exigirFlutter();

        // Dos dominios y no uno, por lo mismo que en el catálogo de intenciones: la veterinaria
        // tiene herencia y una clase abstracta. Un solo dominio deja sin ejercitar justo las
        // combinaciones que rompen.
        Map<String, String> archivos = generador.generar(
                leer("reference-diagrams/veterinaria.json"), "Veterinaria", null);

        escribir(archivos, destino);
        Resultado resultado = analizar(destino);

        assertThat(resultado.codigo())
                .as("flutter analyze rechazó la app generada:%n%s", resultado.salida())
                .isZero();
    }

    @Test
    @DisplayName("EL ARNÉS DETECTA: un .dart roto tiene que ponerlo en rojo")
    void elArnesDetectaCodigoRoto(@TempDir Path destino) throws Exception {
        exigirFlutter();

        Map<String, String> archivos = new java.util.LinkedHashMap<>(generador.generar(
                leer("reference-diagrams/barberia.json"), "Barberia Central", null));

        // Sin esto, todo lo demás de este archivo podría estar midiendo cualquier cosa y dando
        // verde igual. Un arnés que nunca se vio fallar no se sabe qué cubre — y éste corre un
        // proceso externo, que es donde más fácil es que el verde signifique "no llegué a
        // ejecutar nada".
        // El archivo roto va en un SUBDIRECTORIO y no en lib/ a secas. Que analyze corra no
        // prueba que mire lib/ui/ y lib/nlu/, que es donde vive casi toda la app: un arnes que
        // solo ejercita el primer nivel podria dar verde ignorando nueve de los once archivos.
        archivos.put("lib/ui/roto.dart", """
            int sumar(int a, int b) => a + b;

            void roto() {
              String x = sumar(1, 2);
              x.metodoQueNoExiste();
            }
            """);

        escribir(archivos, destino);
        Resultado resultado = analizar(destino);

        assertThat(resultado.codigo())
                .as("el arnés dio verde sobre código que no analiza: no está verificando nada")
                .isNotZero();
        assertThat(resultado.salida())
                .contains("invalid_assignment")
                .contains("undefined_method");
    }

    // --------------------------------------------------------------- el arnés

    private record Resultado(int codigo, String salida) {}

    private static void exigirFlutter() {
        Assumptions.assumeTrue(
                hayFlutter(),
                "SIN VERIFICAR: Flutter no está en el PATH, así que la app generada no se "
                        + "analizó. El verde de esta suite NO cubre el código de la app móvil.");
    }

    private static boolean hayFlutter() {
        try {
            return ejecutar(Path.of("."), 30, "flutter", "--version").codigo() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void escribir(Map<String, String> archivos, Path destino) throws Exception {
        for (Map.Entry<String, String> archivo : archivos.entrySet()) {
            Path ruta = destino.resolve(archivo.getKey());
            Files.createDirectories(ruta.getParent());
            Files.writeString(ruta, archivo.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static Resultado analizar(Path proyecto) throws Exception {
        // --offline primero: con la caché tibia no toca la red, y así el arnés no depende de
        // que haya internet en cada corrida. Si falta algo, se reintenta en línea.
        Resultado dependencias = ejecutar(proyecto, TIMEOUT_SEGUNDOS, "flutter", "pub", "get", "--offline");
        if (dependencias.codigo() != 0) {
            dependencias = ejecutar(proyecto, TIMEOUT_SEGUNDOS, "flutter", "pub", "get");
        }
        if (dependencias.codigo() != 0) {
            // No se disfraza de fallo de análisis: si las dependencias no resuelven, el
            // problema es el pubspec generado y el mensaje tiene que decir eso.
            return new Resultado(dependencias.codigo(),
                    "flutter pub get falló antes de analizar:\n" + dependencias.salida());
        }
        return ejecutar(proyecto, TIMEOUT_SEGUNDOS, "flutter", "analyze");
    }

    private static Resultado ejecutar(Path directorio, int segundos, String... comando)
            throws Exception {

        ProcessBuilder constructor = new ProcessBuilder(enWindows(comando))
                .directory(directorio.toFile())
                .redirectErrorStream(true);

        Process proceso = constructor.start();
        String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proceso.waitFor(segundos, TimeUnit.SECONDS)) {
            proceso.destroyForcibly();
            return new Resultado(-1, "el comando no terminó en " + segundos + " s:\n" + salida);
        }
        return new Resultado(proceso.exitValue(), salida);
    }

    /**
     * En Windows {@code flutter} es un {@code .bat}, y {@code ProcessBuilder} no ejecuta scripts
     * de shell directamente: hay que pasarlos por {@code cmd}. Sin esto el arnés falla con un
     * "no se encuentra el archivo" que parece que Flutter no estuviera instalado.
     */
    private static List<String> enWindows(String... comando) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            return List.of(comando);
        }
        java.util.List<String> conCmd = new java.util.ArrayList<>(List.of("cmd", "/c"));
        conCmd.addAll(List.of(comando));
        return conCmd;
    }
}
