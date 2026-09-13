package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.generator.service.ZipPackagingService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU14: el ZIP que descarga el usuario. */
class ZipPackagingServiceTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final ZipPackagingService zipService = new ZipPackagingService();
    private final SpringBootGeneratorService generador = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), new IdentifierSanitizer());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    private record Entrada(String nombre, int modo, String contenido) {}

    /**
     * Se lee con {@link ZipFile} y no con {@code ZipArchiveInputStream}: el lector en flujo ve
     * solo las cabeceras locales, y los permisos POSIX viven en el <b>directorio central</b>, al
     * final del archivo. Con el lector en flujo {@code getUnixMode()} devuelve siempre 0 — o sea
     * que el test habria dicho "no hay permiso de ejecucion" aunque el ZIP estuviera perfecto.
     */
    private static List<Entrada> leerZip(byte[] zip) throws Exception {
        List<Entrada> entradas = new ArrayList<>();
        try (ZipFile archivo = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip))
                .get()) {
            var iterador = archivo.getEntries().asIterator();
            while (iterador.hasNext()) {
                ZipArchiveEntry entrada = iterador.next();
                try (InputStream in = archivo.getInputStream(entrada)) {
                    entradas.add(new Entrada(
                            entrada.getName(),
                            entrada.getUnixMode(),
                            new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        }
        return entradas;
    }

    @Test
    @DisplayName("El ZIP trae todos los archivos generados con su ruta")
    void contieneTodo() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("reference-diagrams/barberia.json"), "com.barberia.app", "barberia");

        List<Entrada> entradas = leerZip(zipService.empaquetar(archivos));

        assertThat(entradas).hasSize(archivos.size());
        assertThat(entradas).extracting(Entrada::nombre).contains(
                "pom.xml",
                "README.md",
                "mvnw",
                "mvnw.cmd",
                ".mvn/wrapper/maven-wrapper.properties",
                "src/main/java/com/barberia/app/Application.java",
                "src/main/java/com/barberia/app/domain/model/Cita.java",
                "src/main/java/com/barberia/app/repository/CitaRepository.java",
                "src/main/java/com/barberia/app/service/dto/CitaRequestDTO.java",
                "src/main/java/com/barberia/app/service/impl/CitaServiceImpl.java",
                "src/main/java/com/barberia/app/controller/CitaController.java");
    }

    @Test
    @DisplayName("El mvnw sale ejecutable, o el README miente en su primer comando")
    void elWrapperEsEjecutable() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("reference-diagrams/barberia.json"), "com.barberia.app", "barberia");

        List<Entrada> entradas = leerZip(zipService.empaquetar(archivos));

        // Sin el bit de ejecucion, "./mvnw clean compile" en Linux o macOS da Permission denied
        // — y el wrapper viaja justamente para que no haga falta instalar nada.
        assertThat(entradas)
                .filteredOn(e -> e.nombre().equals("mvnw"))
                .singleElement()
                .satisfies(e -> assertThat(e.modo() & 0111)
                        .as("el mvnw tiene que tener permiso de ejecucion")
                        .isNotZero());

        // Y lo que no es un script no lo necesita.
        assertThat(entradas)
                .filteredOn(e -> e.nombre().equals("pom.xml"))
                .singleElement()
                .satisfies(e -> assertThat(e.modo() & 0111).isZero());
    }

    @Test
    @DisplayName("El contenido del ZIP es idéntico al que produjo el generador")
    void elContenidoSobreviveIntacto() throws Exception {
        Map<String, String> archivos =
                generador.generar(leer("edge-cases.json"), "com.empresa.app", "demo");

        Map<String, String> delZip = new LinkedHashMap<>();
        leerZip(zipService.empaquetar(archivos))
                .forEach(e -> delZip.put(e.nombre(), e.contenido()));

        // Es el ZIP lo que se descarga, no el mapa: si el empaquetado alterara algo —una
        // codificacion, un salto de linea— el arnes de compilacion no se enteraria, porque
        // compila el mapa.
        assertThat(delZip).isEqualTo(archivos);
    }

    @Test
    @DisplayName("Un ZIP vacío no rompe el empaquetador")
    void zipVacio() {
        assertThat(zipService.empaquetar(Map.of())).isNotEmpty();
    }
}
