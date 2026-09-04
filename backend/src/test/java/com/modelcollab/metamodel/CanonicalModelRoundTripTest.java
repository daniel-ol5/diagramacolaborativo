package com.modelcollab.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.CanonicalModel;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criterio de aceptación de B-1: los tres diagramas de referencia deben sobrevivir un
 * ciclo JSON -> CanonicalModel -> JSON sin perder ni alterar un solo campo.
 *
 * <p>Este test es el que congela el contrato de §7. Si falla después de tocar los records,
 * el cambio rompe simultáneamente al lienzo web, al generador de código y al exportador XMI.
 */
class CanonicalModelRoundTripTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    @ParameterizedTest(name = "round-trip sin pérdida: {0}.json")
    @ValueSource(strings = {"barberia", "veterinaria", "inventario"})
    @DisplayName("El modelo canónico serializa y deserializa sin pérdida de información")
    void roundTripSinPerdida(String nombre) throws Exception {
        String original = leerRecurso(nombre);

        CanonicalModel modelo = mapper.readValue(original, CanonicalModel.class);
        String resultado = mapper.writeValueAsString(modelo);

        JsonNode arbolOriginal = mapper.readTree(original);
        JsonNode arbolResultado = mapper.readTree(resultado);

        assertThat(arbolResultado)
                .as("round-trip de %s.json", nombre)
                .isEqualTo(arbolOriginal);
    }

    @ParameterizedTest(name = "contenido mínimo: {0}.json")
    @ValueSource(strings = {"barberia", "veterinaria", "inventario"})
    @DisplayName("Los diagramas de referencia tienen clases, relaciones y al menos un método")
    void contenidoMinimo(String nombre) throws Exception {
        CanonicalModel modelo = mapper.readValue(leerRecurso(nombre), CanonicalModel.class);

        assertThat(modelo.schemaVersion()).isEqualTo(CanonicalModel.CURRENT_SCHEMA_VERSION);
        assertThat(modelo.mutationVersion()).isPositive();
        assertThat(modelo.packages()).isNotEmpty();
        assertThat(modelo.classes()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(modelo.relationships()).isNotEmpty();
        assertThat(modelo.classes()).anySatisfy(c -> assertThat(c.methods()).isNotEmpty());
        assertThat(modelo.classes())
                .allSatisfy(c -> assertThat(c.attributes()).isNotEmpty());

        // Toda relación apunta a clases existentes del propio diagrama.
        var ids = modelo.classes().stream().map(c -> c.id()).toList();
        assertThat(modelo.relationships()).allSatisfy(r -> {
            assertThat(ids).contains(r.sourceClassId());
            assertThat(ids).contains(r.targetClassId());
        });
    }

    private String leerRecurso(String nombre) throws Exception {
        String ruta = "reference-diagrams/" + nombre + ".json";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ruta)) {
            assertThat(in).as("recurso %s", ruta).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
