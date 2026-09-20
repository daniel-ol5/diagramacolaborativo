package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.dto.SchemaPreview;
import com.modelcollab.generator.dto.SchemaTable;
import com.modelcollab.generator.service.SchemaPreviewService;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Una clase relacionada <b>consigo misma</b>: {@code Empleado} supervisa a {@code Empleado},
 * {@code Materia} es prerrequisito de {@code Materia}.
 *
 * <h2>Los dos defectos que esto destapó, y por qué ninguno se veía</h2>
 *
 * <p>Es un caso de modelado común y legítimo que <b>nadie había probado</b>. Los tres diagramas de
 * referencia no tenían ninguna recursiva, así que los arneses de compilación, de mapeo y de DTO
 * daban verde sin ejercitarla. El validador tampoco decía nada — y no porque la aceptara a
 * conciencia, sino porque el caso no existía para él.
 *
 * <p><b>Defecto 1 — faltaba la mitad de la relación.</b> El generador preguntaba "¿soy el
 * origen?" y, si sí, se quedaba ahí. Con origen y destino en la misma clase las dos respuestas
 * son verdaderas, así que salía <b>sólo el lado inverso</b>: un
 * {@code @OneToMany(mappedBy = "supervisor")} apuntando a un campo {@code supervisor} que no
 * existía. Compila perfecto; <b>Hibernate lo rechaza al arrancar</b>.
 *
 * <p><b>Defecto 2 — la tabla intermedia con dos columnas del mismo nombre.</b> Un muchos-a-muchos
 * consigo mismo produce una intermedia cuyas dos columnas apuntan a la misma tabla, y cada una se
 * derivaba por separado del nombre de la clase: las dos salían {@code materia_id}. Es un
 * {@code CREATE TABLE} que <b>ni siquiera se puede ejecutar</b>.
 *
 * <p>Y la vista Entidad-Relación mostraba exactamente lo mismo —{@code materia_id} dos veces—
 * porque sale del mismo modelo. <b>La pantalla mentía igual que el ZIP</b>, así que compararlas
 * no habría delatado nada: es el caso donde confrontar dos salidas no alcanza, y hace falta una
 * regla que diga qué es imposible.
 */
class RelacionRecursivaTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), sanitizer);

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    private String entidad(String clase) throws Exception {
        Map<String, String> generado = backend.generar(
                leer("reference-diagrams/recursivas.json"), "com.empresa.app", "demo");
        String java = generado.get(
                "src/main/java/com/empresa/app/domain/model/" + clase + ".java");
        assertThat(java).as("no se generó %s", clase).isNotNull();
        return java;
    }

    @Test
    @DisplayName("Una asociación consigo misma genera LOS DOS lados, no uno")
    void laRecursivaGeneraLosDosLados() throws Exception {
        String java = entidad("Empleado");

        // El lado dueño: el que lleva la clave foránea.
        assertThat(java)
                .as("falta el lado DUEÑO: sin él no hay columna donde guardar el supervisor")
                .contains("private Empleado supervisor;");
        assertThat(java).contains("@JoinColumn(name = \"supervisor_id\")");

        // Y el inverso, que es el que ya salía.
        assertThat(java).contains("private List<Empleado> supervisados");

        // LO QUE DE VERDAD IMPORTA: que el mappedBy apunte a un campo QUE EXISTE. Antes decía
        // mappedBy = "supervisor" y no había ningún campo supervisor — compila igual, y
        // Hibernate lo rechaza recién al arrancar, que es lo más tarde posible.
        Matcher m = Pattern.compile("mappedBy = \"(\\w+)\"").matcher(java);
        assertThat(m.find()).as("la recursiva tiene que tener un lado con mappedBy").isTrue();
        assertThat(java)
                .as("mappedBy apunta a un campo inexistente: Hibernate no arranca")
                .contains("private Empleado " + m.group(1) + ";");
    }

    @Test
    @DisplayName("Un muchos-a-muchos consigo mismo no puede dar dos columnas iguales")
    void laIntermediaRecursivaTieneDosColumnasDistintas() throws Exception {
        String java = entidad("Materia");

        List<String> columnas = new ArrayList<>();
        Matcher m = Pattern.compile("@JoinColumn\\(name = \"([^\"]+)\"\\)").matcher(java);
        while (m.find()) {
            columnas.add(m.group(1));
        }

        assertThat(columnas).as("el @JoinTable tiene que declarar sus dos columnas").hasSize(2);
        // Con las dos iguales, el CREATE TABLE de la intermedia no se puede ejecutar.
        assertThat(columnas.get(0))
                .as("las dos columnas de la intermedia son la MISMA: ese DDL no corre")
                .isNotEqualTo(columnas.get(1));
    }

    @Test
    @DisplayName("Y SIN ROLES tampoco: el desempate no puede depender de que alguien los escriba")
    void sinRolesTambienSalenDistintas() {
        // El fixture de arriba tiene roles ("requiere" / "esPrerrequisitoDe"), asi que los
        // nombres salen distintos por si solos y NO ejercitan el desempate. Lo descubri probando
        // la sonda en rojo: rompi el desempate y el test siguio verde, o sea que no lo estaba
        // midiendo. Una recursiva sin roles es el caso que si lo necesita — y es el mas probable,
        // porque los roles son opcionales y casi nadie los completa.
        List<String> conRoles = sanitizer.columnasDeTablaIntermedia(
                "requiere", "Materia", "esPrerrequisitoDe", "Materia");
        assertThat(conRoles.get(0)).isNotEqualTo(conRoles.get(1));

        List<String> sinRoles = sanitizer.columnasDeTablaIntermedia(null, "Materia", null, "Materia");
        assertThat(sinRoles.get(0))
                .as("sin roles, las dos columnas de una recursiva saldrian iguales")
                .isNotEqualTo(sinRoles.get(1));
        assertThat(sinRoles).containsExactly("materia_origen_id", "materia_destino_id");

        // Y que el desempate NO se meta donde no hace falta: dos clases distintas ya se
        // distinguen solas, y renombrarles las columnas seria cambiar el esquema de todos los
        // diagramas que ya andan.
        assertThat(sanitizer.columnasDeTablaIntermedia(null, "Cita", null, "Servicio"))
                .containsExactly("cita_id", "servicio_id");
    }

    @ParameterizedTest(name = "en {0} ninguna tabla repite el nombre de una columna")
    @ValueSource(strings = {
        "edge-cases.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/barberia.json",
        "reference-diagrams/inventario.json",
        "reference-diagrams/recursivas.json",
    })
    @DisplayName("Ninguna tabla del esquema repite el nombre de una columna")
    void ningunaTablaRepiteColumna(String fixture) throws Exception {
        // Esta es la regla general que el caso recursivo hizo falta descubrir. Vale para todos:
        // dos columnas con el mismo nombre en una tabla es un esquema que no existe, venga de
        // donde venga. Y NO se puede detectar comparando la vista previa con el ZIP —las dos
        // salen del mismo modelo y mienten igual—, así que tiene que ser una regla propia.
        SchemaPreview previa = new SchemaPreviewService(sanitizer).calcular(leer(fixture), 1L);
        assertThat(previa.tables()).as("no salió ninguna tabla: el test no prueba nada").isNotEmpty();

        List<String> repetidas = new ArrayList<>();
        for (SchemaTable tabla : previa.tables()) {
            Set<String> vistas = new LinkedHashSet<>();
            for (var columna : tabla.columns()) {
                if (!vistas.add(columna.name())) {
                    repetidas.add(tabla.name() + "." + columna.name());
                }
            }
        }
        assertThat(repetidas)
                .as("hay tablas con dos columnas del mismo nombre: ese CREATE TABLE no se ejecuta")
                .isEmpty();
    }
}
