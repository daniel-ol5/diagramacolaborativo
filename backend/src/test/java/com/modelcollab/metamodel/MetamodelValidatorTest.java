package com.modelcollab.metamodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.dto.ValidationReportDTO.ValidationIssue;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.MetamodelValidator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CU13, probado en <b>las dos direcciones</b>.
 *
 * <p>Un validador que sólo se prueba con diagramas válidos no se probó: uno que devuelve
 * "siempre válido" pasaría esa mitad entera. Y uno probado sólo con inválidos podría estar
 * rechazando todo. Hace falta comprobar que rechaza lo que debe rechazar <b>y</b> que no rechaza
 * lo que debe pasar.
 *
 * <p>Los datos existen desde el ciclo 1 y se escribieron para esto: {@code invalid-cases/} son
 * modelos estructuralmente válidos según §7 —deserializan sin problema— pero semánticamente
 * imposibles, y {@code edge-cases.json} junta todo lo raro que igual tiene que pasar.
 */
class MetamodelValidatorTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final MetamodelValidator validator = new MetamodelValidator(new IdentifierSanitizer());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    private static List<String> codigos(List<ValidationIssue> hallazgos) {
        return hallazgos.stream().map(ValidationIssue::code).toList();
    }

    // ------------------------------------------------ direccion 1: deja pasar

    @ParameterizedTest(name = "{0} es valido")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("Los tres diagramas de referencia pasan sin un solo error bloqueante")
    void losDominiosPasan(String fixture) throws Exception {
        ValidationReportDTO informe = validator.validar(leer(fixture));

        assertThat(informe.valid())
                .as("errores en %s: %s", fixture, informe.errors())
                .isTrue();
        assertThat(informe.errors()).isEmpty();
    }

    @Test
    @DisplayName("edge-cases pasa: lo raro pero válido NO se bloquea")
    void losCasosLimitePasan() throws Exception {
        ValidationReportDTO informe = validator.validar(leer("edge-cases.json"));

        // Palabras reservadas, clase sin PK, clase vacía, relaciones simétricas: nada de eso
        // impide generar. Bloquearlo sería una herramienta que rechaza modelos correctos.
        assertThat(informe.valid())
                .as("errores inesperados: %s", informe.errors())
                .isTrue();
    }

    @Test
    @DisplayName("…pero sí avisa de todo lo que va a decidir por su cuenta")
    void losCasosLimiteAvisan() throws Exception {
        ValidationReportDTO informe = validator.validar(leer("edge-cases.json"));
        List<String> avisos = codigos(informe.warnings());

        assertThat(avisos).contains(
                MetamodelValidator.CLAVE_INYECTADA,       // SinPrimaryKey y SinAtributos
                MetamodelValidator.CLASE_SIN_ATRIBUTOS,   // SinAtributos
                MetamodelValidator.RESERVADA_SQL,         // Order, Group, Table
                MetamodelValidator.RESERVADA_JAVA,        // class, public, int
                MetamodelValidator.OWNING_SIDE_NO_DERIVABLE); // Cuenta↔Credencial, Suscripcion↔Cupon

        // Las dos relaciones simétricas del fixture, ni una más: un M2M 0..* ↔ 0..* también tiene
        // los dos extremos iguales, pero ahí hay colección y NO es este caso.
        assertThat(informe.warnings())
                .filteredOn(w -> w.code().equals(MetamodelValidator.OWNING_SIDE_NO_DERIVABLE))
                .hasSize(2);
    }

    @Test
    @DisplayName("Los avisos nombran el elemento, no su UUID")
    void losAvisosSonLegibles() throws Exception {
        ValidationReportDTO informe = validator.validar(leer("edge-cases.json"));

        assertThat(informe.warnings())
                .filteredOn(w -> w.code().equals(MetamodelValidator.CLAVE_INYECTADA))
                .anySatisfy(w -> {
                    assertThat(w.elementName()).isEqualTo("SinPrimaryKey");
                    assertThat(w.message()).contains("SinPrimaryKey");
                    assertThat(w.elementId()).isNotBlank();
                });
    }

    // -------------------------------------------------- direccion 2: rechaza

    @Test
    @DisplayName("§11.2.3: herencia múltiple es error BLOQUEANTE, no advertencia")
    void herenciaMultipleBloquea() throws Exception {
        ValidationReportDTO informe = validator.validar(leer("invalid-cases/herencia-multiple.json"));

        assertThat(informe.valid()).isFalse();
        assertThat(codigos(informe.errors())).contains(MetamodelValidator.HERENCIA_MULTIPLE);
        // No hay forma de generar una clase con dos padres: elegir uno sería inventar el modelo.
        assertThat(informe.errors())
                .filteredOn(e -> e.code().equals(MetamodelValidator.HERENCIA_MULTIPLE))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.elementName()).isEqualTo("Hijo");
                    assertThat(e.message()).contains("Java no soporta herencia múltiple");
                    // El mensaje tiene que nombrar LOS DOS padres: con uno solo, el modelador no
                    // sabe cuál de las dos herencias borrar.
                    assertThat(e.message()).contains("PadreA").contains("PadreB");
                });
    }

    @Test
    @DisplayName("Una relación huérfana es error BLOQUEANTE")
    void relacionHuerfanaBloquea() throws Exception {
        ValidationReportDTO informe = validator.validar(leer("invalid-cases/relacion-huerfana.json"));

        assertThat(informe.valid()).isFalse();
        assertThat(codigos(informe.errors())).contains(MetamodelValidator.RELACION_HUERFANA);
        // Generar con esto produce un @ManyToOne que apunta a una clase inexistente.
        assertThat(informe.errors())
                .filteredOn(e -> e.code().equals(MetamodelValidator.RELACION_HUERFANA))
                .singleElement()
                .satisfies(e -> assertThat(e.message()).contains("no existe en el diagrama"));
    }

    @Test
    @DisplayName("Un diagrama vacío no se puede generar")
    void diagramaVacio() {
        ValidationReportDTO informe = validator.validar(CanonicalModel.empty());

        assertThat(informe.valid()).isFalse();
        assertThat(codigos(informe.errors())).containsExactly(MetamodelValidator.DIAGRAMA_VACIO);
    }

    @Test
    @DisplayName("Dos clases que generan el mismo nombre Java se bloquean")
    void nombresQueColisionan() throws Exception {
        // "Detalle de Orden" y "detalle-de-orden" son nombres distintos en el diagrama y la MISMA
        // clase Java: una se pisaría a la otra en el ZIP, en silencio.
        CanonicalModel modelo = mapper.readValue("""
                {"schemaVersion":"1.0.0","mutationVersion":1,"packages":[],"relationships":[],
                 "classes":[
                   {"id":"c1","name":"Detalle de Orden","position":{"x":0.0,"y":0.0},
                    "attributes":[{"id":"a1","name":"id","type":"UUID","isPrimaryKey":true}]},
                   {"id":"c2","name":"detalle-de-orden","position":{"x":0.0,"y":0.0},
                    "attributes":[{"id":"a2","name":"id","type":"UUID","isPrimaryKey":true}]}]}
                """, CanonicalModel.class);

        ValidationReportDTO informe = validator.validar(modelo);

        assertThat(informe.valid()).isFalse();
        assertThat(codigos(informe.errors())).contains(MetamodelValidator.NOMBRE_DUPLICADO);
    }

    @Test
    @DisplayName("Dos atributos que generan el mismo campo se bloquean")
    void atributosQueColisionan() throws Exception {
        CanonicalModel modelo = mapper.readValue("""
                {"schemaVersion":"1.0.0","mutationVersion":1,"packages":[],"relationships":[],
                 "classes":[
                   {"id":"c1","name":"Cliente","position":{"x":0.0,"y":0.0},
                    "attributes":[
                      {"id":"a1","name":"id","type":"UUID","isPrimaryKey":true},
                      {"id":"a2","name":"nombre completo","type":"VARCHAR"},
                      {"id":"a3","name":"nombreCompleto","type":"VARCHAR"}]}]}
                """, CanonicalModel.class);

        ValidationReportDTO informe = validator.validar(modelo);

        assertThat(informe.valid()).isFalse();
        assertThat(codigos(informe.errors())).contains(MetamodelValidator.ATRIBUTO_DUPLICADO);
    }

    @Test
    @DisplayName("Una jerarquía circular se bloquea en vez de colgar al generador")
    void herenciaCircular() throws Exception {
        CanonicalModel modelo = mapper.readValue("""
                {"schemaVersion":"1.0.0","mutationVersion":1,"packages":[],
                 "classes":[
                   {"id":"c1","name":"A","position":{"x":0.0,"y":0.0},"attributes":[]},
                   {"id":"c2","name":"B","position":{"x":0.0,"y":0.0},"attributes":[]}],
                 "relationships":[
                   {"id":"r1","sourceClassId":"c1","targetClassId":"c2","type":"INHERITANCE"},
                   {"id":"r2","sourceClassId":"c2","targetClassId":"c1","type":"INHERITANCE"}]}
                """, CanonicalModel.class);

        ValidationReportDTO informe = validator.validar(modelo);

        assertThat(informe.valid()).isFalse();
        assertThat(codigos(informe.errors())).contains(MetamodelValidator.HERENCIA_CIRCULAR);
    }

    // ------------------------------------------------------------- el informe

    @Test
    @DisplayName("Un diagrama con advertencias y sin errores SIGUE siendo válido")
    void lasAdvertenciasNoBloquean() throws Exception {
        ValidationReportDTO informe = validator.validar(leer("edge-cases.json"));

        // Es la distinción que sostiene todo: si las advertencias bloquearan, la barbería no se
        // podría generar porque Cita↔Pago avisa del lado de la FK.
        assertThat(informe.warnings()).isNotEmpty();
        assertThat(informe.valid()).isTrue();
    }

    @Test
    @DisplayName("El validador no guarda estado entre llamadas")
    void sinEstadoEntreLlamadas() throws Exception {
        // Es un singleton de Spring: si guardara estado, validar un diagrama inválido dejaría
        // contaminado al siguiente. Se comprueba alternando a propósito.
        assertThat(validator.validar(leer("invalid-cases/herencia-multiple.json")).valid()).isFalse();
        assertThat(validator.validar(leer("reference-diagrams/barberia.json")).valid()).isTrue();
        assertThat(validator.validar(leer("invalid-cases/relacion-huerfana.json")).valid()).isFalse();
        assertThat(validator.validar(leer("reference-diagrams/barberia.json")).valid()).isTrue();
    }
}
