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
 * La <b>columna</b> de una clave foránea y el <b>campo del DTO</b> que la rellena tienen que
 * nombrar la misma cosa.
 *
 * <h2>El defecto que esto habría atrapado, y que vivió mucho tiempo</h2>
 *
 * <p>El campo del DTO se derivaba del <b>rol</b> de la relación y la columna del <b>nombre de la
 * clase</b>. Con {@code targetRole = "titular"} sobre una clase {@code Cliente}, el proyecto
 * generado pedía {@code titularId} por la API y guardaba en {@code cliente_id}.
 *
 * <p>Funcionaba — Hibernate no exige que se llamen igual— pero es la divergencia clásica en su
 * forma más pura: <b>dos nombres para la misma cosa, calculados por dos funciones distintas a
 * partir de dos entradas distintas</b>. El día que alguien mire la base para entender la API, o
 * al revés, no va a encontrar lo que busca. Y apareció de costado, arreglando otra cosa.
 *
 * <h2>Por qué esta comparación SÍ vale, cuando la de la vista previa no valía</h2>
 *
 * <p>Confrontar dos salidas no sirve si las dos salen del mismo cálculo: la vista Entidad-Relación
 * y el ZIP mostraban la misma columna repetida en la intermedia recursiva porque los dos leen
 * {@code ModeloDeGeneracion}.
 *
 * <p>Acá no pasa eso. El campo del DTO sale de {@code campoDeReferencia} y la columna de
 * {@code columnaDeFkDeRelacion}: <b>dos funciones separadas</b>. Que coincidan es una propiedad
 * que se puede perder, y por eso se puede medir.
 */
class ColumnaYCampoCoincidenTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), sanitizer);

    @ParameterizedTest(name = "en {0} la columna y el campo del DTO se llaman igual")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
        "reference-diagrams/recursivas.json",
        "edge-cases.json",
    })
    @DisplayName("Toda clave foránea se llama igual en la base que en la API")
    void laColumnaYElCampoNombranLoMismo(String fixture) throws Exception {
        Map<String, String> generado = backend.generar(leer(fixture), "com.empresa.app", "demo");

        Map<String, Set<String>> camposDelDto = new LinkedHashMap<>();
        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            String nombre = archivo.getKey().substring(archivo.getKey().lastIndexOf('/') + 1);
            if (nombre.endsWith("RequestDTO.java")) {
                camposDelDto.put(nombre.replace("RequestDTO.java", ""),
                        camposDelDto(archivo.getValue()));
            }
        }
        assertThat(camposDelDto).as("no se generó ningún DTO: el test no prueba nada").isNotEmpty();

        List<String> divergencias = new ArrayList<>();
        boolean huboAlguna = false;

        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            if (!archivo.getKey().contains("/domain/model/")) {
                continue;
            }
            String entidad = archivo.getKey()
                    .substring(archivo.getKey().lastIndexOf('/') + 1).replace(".java", "");

            for (Map.Entry<String, String> fk : clavesForaneas(archivo.getValue()).entrySet()) {
                huboAlguna = true;
                String campoJava = fk.getKey();
                String columna = fk.getValue();
                String campoDelDto = campoJava + "Id";

                String esperada = sanitizer.nombreDeColumna(campoDelDto).replace("\"", "");
                if (!columna.replace("\\\"", "").replace("\"", "").equals(esperada)) {
                    divergencias.add(entidad + ": la columna es " + columna
                            + " y el campo del DTO sería " + campoDelDto
                            + " (columna esperada " + esperada + ")");
                }
                Set<String> delDto = camposDelDto.get(entidad);
                if (delDto != null && !delDto.contains(campoDelDto)) {
                    divergencias.add(entidad + ": el DTO no acepta " + campoDelDto
                            + ", acepta " + delDto);
                }
            }
        }

        assertThat(huboAlguna)
                .as("el fixture tiene que traer claves foráneas para que esto signifique algo")
                .isTrue();
        assertThat(divergencias)
                .as("la base y la API le dan nombres distintos a la misma clave foránea")
                .isEmpty();
    }

    /** Campo Java de la relación -> nombre de columna, leídos del {@code @JoinColumn}. */
    private static Map<String, String> clavesForaneas(String java) {
        Map<String, String> fks = new LinkedHashMap<>();
        String columna = null;

        for (String linea : java.split("\n")) {
            String limpia = linea.strip();
            // PRIMERO el descarte, y no despues: las columnas de un @JoinTable van en lineas
            // que TAMBIEN contienen "@JoinColumn(name = ", asi que si se extrae antes de
            // descartar, las de la intermedia entran igual. Este test fallo asi la primera vez
            // y el mensaje culpaba al DTO de no aceptar un campo que nunca deberia aceptar.
            if (limpia.startsWith("joinColumns") || limpia.startsWith("inverseJoinColumns")) {
                columna = null;
                continue;
            }
            int i = limpia.indexOf("@JoinColumn(name = \"");
            if (i >= 0) {
                String resto = limpia.substring(i + "@JoinColumn(name = \"".length());
                columna = resto.substring(0, resto.indexOf('"'));
                continue;
            }
            if (columna != null && limpia.startsWith("private ")) {
                String[] partes = limpia.replace(";", "").split("\\s+");
                if (partes.length >= 3) {
                    fks.put(partes[2].split("=")[0].strip(), columna);
                }
                columna = null;
            }
        }
        return fks;
    }

    private static Set<String> camposDelDto(String java) {
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

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }
}
