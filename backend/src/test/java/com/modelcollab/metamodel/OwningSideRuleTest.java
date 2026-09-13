package com.modelcollab.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.MetamodelValidator;
import com.modelcollab.metamodel.service.OwningSideRule;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-08: la regla que decide de qué lado va la clave foránea.
 *
 * <h2>El bug que hace que este archivo exista</h2>
 *
 * <p>La regla estaba implementada en el validador y <b>ausente en el generador</b>. El aviso que
 * se le mostraba al modelador decía <i>"El generador va a usar TARGET"</i> y el generador usaba
 * SOURCE. Con {@code 1..1 → 1..*} y {@code owningSide=SOURCE}, el lado de la colección salía
 * anotado {@code @ManyToOne(mappedBy = "...")} — y {@code mappedBy} no existe en
 * {@code @ManyToOne}. <b>El ZIP no compilaba.</b>
 *
 * <p>Apareció generando código a partir de un diagrama importado desde una foto, porque ahí el
 * {@code owningSide} nunca lo elige una persona: queda en el valor por defecto del schema, que
 * acierta la mitad de las veces por casualidad. Los diagramas de referencia estaban escritos a
 * mano con el valor correcto, así que lo tapaban.
 *
 * <p>Es el riesgo R-04 del plan —"el código generado no compila en la defensa en vivo"— disparado
 * exactamente por el campo que D-08 declara no confiable.
 */
class OwningSideRuleTest {

    private static final String FIXTURE = "reference-diagrams/owning-side-contradictorio.json";

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    private static Relationship relacion(
            RelationshipType tipo, OwningSide declarado, Multiplicity sm, Multiplicity tm) {
        return new Relationship("r", "a", "b", tipo, declarado, null, sm, tm,
                null, null, null, List.of());
    }

    private CanonicalModel fixture() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("falta el fixture %s", FIXTURE).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    // ------------------------------------------------------------- la regla

    @Test
    @DisplayName("Con colección, la clave foránea va del lado que puede repetirse")
    void conColeccionMandaLaMultiplicidad() {
        // 1..1 → 1..*: muchos destinos por origen, así que la columna vive en el destino.
        // El diagrama dice SOURCE y se ignora: por eso el ZIP ahora compila.
        assertThat(OwningSideRule.efectivo(relacion(RelationshipType.COMPOSITION,
                OwningSide.SOURCE, Multiplicity.ONE_ONE, Multiplicity.ONE_MANY)))
                .isEqualTo(OwningSide.TARGET);

        assertThat(OwningSideRule.efectivo(relacion(RelationshipType.ASSOCIATION,
                OwningSide.TARGET, Multiplicity.ZERO_MANY, Multiplicity.ONE_ONE)))
                .isEqualTo(OwningSide.SOURCE);
    }

    @Test
    @DisplayName("Sin colección pero con extremos distintos, va en el extremo flojo")
    void sinColeccionVaEnElFlojo() {
        // Poner la columna del lado 1..1 obligaría a tener siempre la otra fila.
        assertThat(OwningSideRule.efectivo(relacion(RelationshipType.ASSOCIATION,
                OwningSide.TARGET, Multiplicity.ZERO_ONE, Multiplicity.ONE_ONE)))
                .isEqualTo(OwningSide.SOURCE);
    }

    @Test
    @DisplayName("En MANY_TO_MANY manda el diagrama: la FK no está en ninguna de las dos tablas")
    void manyToManyEsAutoritativo() {
        // El lado dueño dice de qué lado se escribe la @JoinTable, y eso es una elección
        // genuina del modelador, no algo que las multiplicidades puedan responder.
        Relationship m2m = relacion(RelationshipType.MANY_TO_MANY,
                OwningSide.TARGET, Multiplicity.ZERO_MANY, Multiplicity.ZERO_MANY);

        assertThat(OwningSideRule.esDerivable(m2m)).isFalse();
        assertThat(OwningSideRule.efectivo(m2m)).isEqualTo(OwningSide.TARGET);
    }

    @Test
    @DisplayName("En una relación escalar simétrica también manda el diagrama")
    void simetricaEsAutoritativa() {
        Relationship simetrica = relacion(RelationshipType.ASSOCIATION,
                OwningSide.TARGET, Multiplicity.ONE_ONE, Multiplicity.ONE_ONE);

        // Las dos tablas pueden llevar la columna igual de bien: no hay respuesta mecánica.
        assertThat(OwningSideRule.esDerivable(simetrica)).isFalse();
        assertThat(OwningSideRule.efectivo(simetrica)).isEqualTo(OwningSide.TARGET);
    }

    // ------------------------------------- el fixture de regresión sirve

    @Test
    @DisplayName("Las TRES relaciones del fixture contradicen de verdad: el caso no es vacío")
    void elFixtureContradiceDeVerdad() throws Exception {
        CanonicalModel modelo = fixture();

        // Sin esto, alguien podría "arreglar" el fixture poniéndole los owningSide correctos y
        // el caso del arnés seguiría en verde sin probar nada. Un arnés que dejó de cubrir lo
        // que fue a cubrir es peor que no tenerlo, porque nadie lo sospecha.
        assertThat(modelo.relationships()).hasSize(3);
        for (Relationship r : modelo.relationships()) {
            assertThat(OwningSideRule.esDerivable(r))
                    .as("la relación %s tiene que ser derivable para que el caso signifique algo",
                            r.id())
                    .isTrue();
            assertThat(OwningSideRule.efectivo(r))
                    .as("la relación %s ya declara el lado correcto: no contradice nada", r.id())
                    .isNotEqualTo(r.owningSide());
        }
    }

    @Test
    @DisplayName("Ninguna entidad generada lleva @ManyToOne(mappedBy): no existe en JPA")
    void ningunManyToOneConMappedBy() throws Exception {
        Map<String, String> archivos = new SpringBootGeneratorService(
                new com.modelcollab.config.FreeMarkerConfig().freemarkerConfiguration(),
                new IdentifierSanitizer())
                .generar(fixture(), "com.empresa.app", "demo");

        // Es la forma exacta del fallo. El compilador ya lo atrapa en el arnés, pero un mensaje
        // que diga "cannot find symbol: method mappedBy()" obliga a reconstruir de dónde salió;
        // este test nombra el problema.
        for (Map.Entry<String, String> archivo : archivos.entrySet()) {
            assertThat(archivo.getValue())
                    .as("%s anota mappedBy en un @ManyToOne", archivo.getKey())
                    .doesNotContain("@ManyToOne(mappedBy");
        }
    }

    @Test
    @DisplayName("El aviso del validador promete exactamente lo que el generador hace")
    void elAvisoNoMiente() throws Exception {
        CanonicalModel modelo = fixture();

        ValidationReportDTO informe =
                new MetamodelValidator(new IdentifierSanitizer()).validar(modelo);

        List<ValidationReportDTO.ValidationIssue> avisos = informe.warnings().stream()
                .filter(w -> MetamodelValidator.OWNING_SIDE_CONTRADICE.equals(w.code()))
                .toList();
        assertThat(avisos).hasSize(3);

        // El aviso dice "El generador va a usar X". Que lo diga y no lo haga fue el bug; que
        // las dos cosas salgan de la misma función es lo que impide que vuelva a pasar.
        for (ValidationReportDTO.ValidationIssue aviso : avisos) {
            Relationship r = modelo.relationships().stream()
                    .filter(x -> x.id().equals(aviso.elementId()))
                    .findFirst().orElseThrow();
            assertThat(aviso.message())
                    .contains("El generador va a usar " + OwningSideRule.efectivo(r).name());
        }
    }
}
