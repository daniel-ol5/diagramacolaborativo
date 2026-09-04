package com.modelcollab.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.RelationshipType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Criterio de aceptación de B-1: todos los fixtures deben sobrevivir un ciclo
 * JSON -> CanonicalModel -> JSON sin perder ni alterar un solo campo.
 *
 * <p>Este test es el que congela el contrato de §7. Si falla después de tocar los records,
 * el cambio rompe simultáneamente al lienzo web, al generador de código y al exportador XMI.
 *
 * <p>Los fixtures son tres grupos con propósitos distintos:
 * <ul>
 *   <li>{@code reference-diagrams/} — los tres dominios que se demuestran ante el tribunal.</li>
 *   <li>{@code edge-cases.json} — casos raros pero <b>válidos</b>, que los dominios naturales
 *       no producen: palabras reservadas, clase sin PK, relaciones simétricas, DEPENDENCY.</li>
 *   <li>{@code invalid-cases/} — modelos estructuralmente válidos según §7 pero
 *       semánticamente inválidos, que el PreGenerationValidatorService debe <b>rechazar</b>
 *       (día 10). Acá sólo se verifica que deserializan; el rechazo se prueba allá.</li>
 * </ul>
 */
class CanonicalModelRoundTripTest {

    private static final List<String> DOMINIOS =
            List.of("barberia", "veterinaria", "inventario");

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    @ParameterizedTest(name = "round-trip sin pérdida: {0}")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
        "edge-cases.json",
        "invalid-cases/herencia-multiple.json",
        "invalid-cases/relacion-huerfana.json",
    })
    @DisplayName("El modelo canónico serializa y deserializa sin pérdida de información")
    void roundTripSinPerdida(String ruta) throws Exception {
        String original = leerRecurso(ruta);

        CanonicalModel modelo = mapper.readValue(original, CanonicalModel.class);
        String resultado = mapper.writeValueAsString(modelo);

        JsonNode arbolOriginal = mapper.readTree(original);
        JsonNode arbolResultado = mapper.readTree(resultado);

        assertThat(arbolResultado)
                .as("round-trip de %s", ruta)
                .isEqualTo(arbolOriginal);
    }

    @ParameterizedTest(name = "contenido mínimo: {0}.json")
    @ValueSource(strings = {"barberia", "veterinaria", "inventario"})
    @DisplayName("Los diagramas de referencia tienen clases, relaciones y al menos un método")
    void contenidoMinimo(String nombre) throws Exception {
        CanonicalModel modelo = leerModelo("reference-diagrams/" + nombre + ".json");

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

    @Test
    @DisplayName("Entre todos los fixtures válidos se usan los 9 AttributeType del schema")
    void cubreTodosLosTiposDeAtributo() throws Exception {
        var vistos = EnumSet.noneOf(AttributeType.class);
        for (String nombre : DOMINIOS) {
            acumularTipos(leerModelo("reference-diagrams/" + nombre + ".json"), vistos);
        }
        acumularTipos(leerModelo("edge-cases.json"), vistos);

        assertThat(vistos)
                .as("un tipo sin fixture es una rama del generador que nunca se ejecuta")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(AttributeType.class));
    }

    @Test
    @DisplayName("edge-cases cubre lo que los dominios naturales no producen")
    void edgeCasesCubreLosBordes() throws Exception {
        CanonicalModel modelo = leerModelo("edge-cases.json");

        // §11.2.1 - palabras reservadas de SQL y de Java.
        assertThat(modelo.classes()).extracting("name").contains("Order", "Group", "Table");
        var nombresDeAtributos = modelo.classes().stream()
                .flatMap(c -> c.attributes().stream())
                .map(a -> a.name())
                .toList();
        assertThat(nombresDeAtributos).contains("class", "public", "int");

        // §11.2.2 - clase sin ningún atributo isPrimaryKey: el generador inyecta la PK.
        assertThat(modelo.classes())
                .filteredOn(c -> c.name().equals("SinPrimaryKey"))
                .singleElement()
                .satisfies(c -> assertThat(c.attributes()).noneMatch(a -> a.isPrimaryKey()));

        // Clase completamente vacía.
        assertThat(modelo.classes())
                .filteredOn(c -> c.name().equals("SinAtributos"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.attributes()).isEmpty();
                    assertThat(c.methods()).isEmpty();
                });

        // Caso 3 de owningSide: ambos extremos escalares e iguales. Es el único caso donde
        // el campo es autoritativo sin poder verificarse, y el MetamodelValidator debe
        // advertir siempre. Ojo que un M2M 0..* <-> 0..* también tiene los dos extremos
        // iguales pero NO es este caso: ahí hay colección y manda la regla 4.
        assertThat(modelo.relationships())
                .filteredOn(r -> r.sourceMultiplicity() != null
                        && !r.sourceMultiplicity().isCollection()
                        && r.sourceMultiplicity() == r.targetMultiplicity())
                .hasSize(2);

        // M2M sin joinTableName: ejercita la derivación {source}_{target}.
        assertThat(modelo.relationships())
                .filteredOn(r -> r.type() == RelationshipType.MANY_TO_MANY)
                .singleElement()
                .satisfies(r -> assertThat(r.joinTableName()).isNull());

        // DEPENDENCY y multiplicidad 1..*, ausentes en los tres dominios.
        assertThat(modelo.relationships())
                .anyMatch(r -> r.type() == RelationshipType.DEPENDENCY);
        assertThat(modelo.relationships())
                .anyMatch(r -> r.targetMultiplicity() == Multiplicity.ONE_MANY);

        // Método con parámetros y tipo de retorno no primitivo.
        assertThat(modelo.classes())
                .flatExtracting(c -> c.methods())
                .anyMatch(m -> m.returnType().equals("List<Order>") && m.parameters().size() == 2);
    }

    @Test
    @DisplayName("Los invalid-cases deserializan bien: son válidos para §7, no para el validador")
    void invalidCasesDeserializanPeroSonSemanticamenteInvalidos() throws Exception {
        // Herencia múltiple (§11.2.3): estructuralmente correcto, imposible en Java.
        CanonicalModel herencia = leerModelo("invalid-cases/herencia-multiple.json");
        var idHijo = herencia.classes().stream()
                .filter(c -> c.name().equals("Hijo")).findFirst().orElseThrow().id();
        assertThat(herencia.relationships())
                .filteredOn(r -> r.type() == RelationshipType.INHERITANCE
                        && r.sourceClassId().equals(idHijo))
                .hasSize(2);

        // Relación huérfana: el residuo que deja un DELETE_CLASS mal aplicado.
        CanonicalModel huerfana = leerModelo("invalid-cases/relacion-huerfana.json");
        var ids = huerfana.classes().stream().map(c -> c.id()).toList();
        assertThat(huerfana.relationships())
                .singleElement()
                .satisfies(r -> assertThat(ids).doesNotContain(r.sourceClassId()));
    }

    private void acumularTipos(CanonicalModel modelo, EnumSet<AttributeType> destino) {
        modelo.classes().forEach(c -> c.attributes().forEach(a -> destino.add(a.type())));
    }

    private CanonicalModel leerModelo(String ruta) throws Exception {
        return mapper.readValue(leerRecurso(ruta), CanonicalModel.class);
    }

    private String leerRecurso(String ruta) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ruta)) {
            assertThat(in).as("recurso %s", ruta).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
