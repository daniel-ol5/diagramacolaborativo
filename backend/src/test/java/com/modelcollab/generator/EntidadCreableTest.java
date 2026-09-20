package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>Toda entidad generada se tiene que poder crear.</b>
 *
 * <h2>Lo que este arnés agrega a los que ya había</h2>
 *
 * <p>El arnés de compilación dice que el código generado compila; el de mapeo dice que Hibernate
 * lo acepta. Los dos daban verde sobre un defecto que hacía <b>imposible crear un {@code Dueno}
 * por la API</b>: su {@code RequestDTO} no llevaba los atributos que hereda de {@code Persona},
 * y como {@code Persona.nombres} es {@code nullable = false}, no había por dónde mandar el dato
 * y el insert violaba el NOT NULL.
 *
 * <p>Compilaba perfecto. Fallaba recién contra un PostgreSQL real, en el primer {@code POST}.
 *
 * <p>Este archivo generaliza ese hallazgo en vez de tapar el caso: la regla no es "acordate de
 * los campos heredados", es
 *
 * <blockquote>
 * toda columna {@code nullable = false} tiene que tener por dónde llegar desde el cliente.
 * </blockquote>
 *
 * <p>Si una columna es obligatoria y el {@code RequestDTO} no la expone, <b>esa entidad no se
 * puede crear</b>, sin importar si el motivo es la herencia o cualquier otro que aparezca
 * después.
 *
 * <h2>Lo que sigue sin cubrir, y hay que decirlo</h2>
 *
 * <p>Esto es estático: lee el Java generado. Comprueba que <i>exista</i> por dónde mandar cada
 * dato obligatorio, no que el alta funcione. Lo único que probaría eso es levantar el proyecto
 * generado contra una base real y hacerle un {@code POST}, y eso todavía no se hizo.
 */
class EntidadCreableTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), new IdentifierSanitizer());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    /** Una entidad generada, ya leída del Java. */
    private record Entidad(String padre, List<String> obligatorios) {}

    @ParameterizedTest(name = "en {0} toda entidad se puede crear por la API")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
        "reference-diagrams/recursivas.json",
        "reference-diagrams/owning-side-contradictorio.json",
        "edge-cases.json",
    })
    @DisplayName("Toda columna nullable=false tiene por dónde llegar desde el cliente")
    void todaColumnaObligatoriaSePuedeEnviar(String fixture) throws Exception {
        Map<String, String> generado = backend.generar(
                leer(fixture), "com.empresa.app", "demo");

        Map<String, Entidad> entidades = new LinkedHashMap<>();
        Map<String, Set<String>> camposDelDto = new LinkedHashMap<>();

        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            String nombre = archivo.getKey()
                    .substring(archivo.getKey().lastIndexOf('/') + 1);

            if (archivo.getKey().contains("/domain/model/") && nombre.endsWith(".java")) {
                entidades.put(nombre.replace(".java", ""), leerEntidad(archivo.getValue()));
            } else if (nombre.endsWith("RequestDTO.java")) {
                camposDelDto.put(
                        nombre.replace("RequestDTO.java", ""), leerCamposDelDto(archivo.getValue()));
            }
        }
        assertThat(entidades).as("no se generó ninguna entidad: el test no prueba nada").isNotEmpty();
        assertThat(camposDelDto).as("no se generó ningún DTO: el test no prueba nada").isNotEmpty();

        List<String> imposibles = new ArrayList<>();
        for (Map.Entry<String, Set<String>> dto : camposDelDto.entrySet()) {
            // Se sube por la jerarquía: lo obligatorio de un ancestro también hay que poder
            // mandarlo, y es justo lo que faltaba.
            for (String obligatorio : obligatoriosCon(dto.getKey(), entidades)) {
                if (!dto.getValue().contains(obligatorio)) {
                    imposibles.add(dto.getKey() + "." + obligatorio
                            + " es obligatorio y el DTO sólo acepta " + dto.getValue());
                }
            }
        }
        assertThat(imposibles)
                .as("hay entidades que NO SE PUEDEN CREAR: una columna obligatoria sin campo en "
                        + "el DTO no tiene por dónde llegar, y el insert viola el NOT NULL")
                .isEmpty();
    }

    /** Lo obligatorio de la clase y de todos sus ancestros. */
    private static Set<String> obligatoriosCon(String clase, Map<String, Entidad> entidades) {
        Set<String> todos = new LinkedHashSet<>();
        String actual = clase;
        int guarda = 0;
        while (actual != null && guarda++ < 32) {
            Entidad entidad = entidades.get(actual);
            if (entidad == null) {
                break;
            }
            todos.addAll(entidad.obligatorios());
            actual = entidad.padre();
        }
        return todos;
    }

    /**
     * Saca de una entidad su padre y los campos que el cliente está obligado a mandar.
     *
     * <p>Se recorre acumulando las líneas de anotación hasta la declaración del campo, porque lo
     * que dice si algo es obligatorio —{@code nullable = false}— está en la anotación anterior y
     * no en la línea del campo.
     */
    private static Entidad leerEntidad(String java) {
        String padre = null;
        int extiende = java.indexOf(" extends ");
        if (extiende > 0) {
            padre = java.substring(extiende + " extends ".length()).split("[\\s{]")[0];
        }

        List<String> obligatorios = new ArrayList<>();
        StringBuilder anotaciones = new StringBuilder();

        for (String linea : java.split("\n")) {
            String limpia = linea.strip();
            if (limpia.startsWith("@") || (anotaciones.length() > 0 && !limpia.startsWith("private"))) {
                anotaciones.append(' ').append(limpia);
                continue;
            }
            if (!limpia.startsWith("private ")) {
                continue;
            }
            String bloque = anotaciones.toString();
            anotaciones.setLength(0);

            String[] partes = limpia.replace(";", "").split("\\s+");
            if (partes.length < 3) {
                continue;
            }
            String tipo = partes[1];
            String campo = partes[2].split("=")[0].strip();

            // La clave primaria la genera el servidor: el cliente no la manda ni debe.
            if (bloque.contains("@Id")) {
                continue;
            }
            // Una colección nunca es una columna: vive en la otra tabla o en la intermedia.
            if (tipo.startsWith("List<") || tipo.startsWith("Set<")) {
                continue;
            }
            if (!bloque.contains("nullable = false")) {
                continue;
            }
            // Una referencia viaja como identificador: el campo del DTO es clienteId.
            obligatorios.add(bloque.contains("@JoinColumn") ? campo + "Id" : campo);
        }
        return new Entidad(padre, obligatorios);
    }

    private static Set<String> leerCamposDelDto(String java) {
        Set<String> campos = new LinkedHashSet<>();
        boolean dentro = false;

        for (String linea : java.split("\n")) {
            String limpia = linea.strip();
            if (limpia.startsWith("public record")) {
                dentro = true;
                continue;
            }
            if (!dentro || limpia.startsWith("@") || limpia.startsWith(") {")) {
                continue;
            }
            String[] partes = limpia.replace(",", "").split("\\s+");
            if (partes.length == 2) {
                campos.add(partes[1]);
            }
        }
        return campos;
    }
}
