package com.modelcollab.assistant.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.MetamodelValidator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pruebas unitarias de las reglas de revisión del diagrama (CU22).
 */
class DiagramReviewRulesEngineTest {

    private ObjectMapper mapper;
    private DiagramReviewRulesEngine rulesEngine;

    @BeforeEach
    void setUp() {
        mapper = JacksonConfig.canonicalObjectMapper();
        MetamodelValidator validator = new MetamodelValidator(new IdentifierSanitizer());
        rulesEngine = new DiagramReviewRulesEngine(validator);
    }

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    @Test
    @DisplayName("CU22 criterio 1: herencia-multiple.json reporta el error bloqueante y califica <= 50")
    void herenciaMultipleReportaErrorBloqueante() throws Exception {
        CanonicalModel model = leer("invalid-cases/herencia-multiple.json");
        DiagramReviewRulesEngine.AnalysisResult result = rulesEngine.analyze(model);

        assertThat(result.totalErrors()).isGreaterThan(0);
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.severity()).isEqualTo(ReviewIssueSeverity.ERROR);
                    assertThat(issue.code()).isEqualTo(MetamodelValidator.HERENCIA_MULTIPLE);
                    assertThat(issue.elementName()).isEqualTo("Hijo");
                });
        assertThat(result.score()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("CU22 criterio 2: edge-cases.json marca la clase SinAtributos")
    void edgeCasesMarcaClaseSinAtributos() throws Exception {
        CanonicalModel model = leer("edge-cases.json");
        DiagramReviewRulesEngine.AnalysisResult result = rulesEngine.analyze(model);

        assertThat(result.totalErrors()).isZero();
        assertThat(result.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.elementName()).isEqualTo("SinAtributos");
                    assertThat(issue.code()).isIn(
                            MetamodelValidator.CLASE_SIN_ATRIBUTOS,
                            DiagramReviewRulesEngine.CLASS_WITHOUT_ATTRIBUTES);
                });
    }

    @Test
    @DisplayName("barberia.json no tiene errores bloqueantes y obtiene puntaje alto")
    void barberiaObtienePuntajeAlto() throws Exception {
        CanonicalModel model = leer("reference-diagrams/barberia.json");
        DiagramReviewRulesEngine.AnalysisResult result = rulesEngine.analyze(model);

        assertThat(result.totalErrors()).isZero();
        assertThat(result.score()).isGreaterThanOrEqualTo(80);
    }

    @Test
    @DisplayName("Fórmula de cálculo de puntaje: base 100, penalizaciones y tope 50 ante errores")
    void formulaCalculaCorrectamente() {
        // Sin problemas -> 100
        assertThat(DiagramReviewRulesEngine.calculateScore(0, 0, 0)).isEqualTo(100);

        // 1 advertencia -> 90
        assertThat(DiagramReviewRulesEngine.calculateScore(0, 1, 0)).isEqualTo(90);

        // 2 sugerencias -> 90
        assertThat(DiagramReviewRulesEngine.calculateScore(0, 0, 2)).isEqualTo(90);

        // 1 error bloqueante -> máximo 50
        assertThat(DiagramReviewRulesEngine.calculateScore(1, 0, 0)).isEqualTo(50);

        // Muchos errores -> no baja de 0
        assertThat(DiagramReviewRulesEngine.calculateScore(10, 10, 10)).isEqualTo(0);
    }
}

