package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.service.FlutterAppGeneratorService;
import com.modelcollab.generator.service.NluIntentGeneratorService;
import com.modelcollab.generator.service.SpanishNounInflector;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Los dos proyectos generados del mismo diagrama tienen que <b>coincidir en las direcciones</b>.
 *
 * <h2>Por qué hace falta un test para esto</h2>
 *
 * <p>El backend expone sus controladores en {@code /api/algo} y la app móvil les pega. Son dos
 * generadores distintos, con dos plantillas distintas, y nada los obliga a estar de acuerdo
 * salvo que alguien lo compruebe.
 *
 * <p>Y ya estuvieron en desacuerdo: la app apuntaba a {@code /api/v1} —el prefijo del backend de
 * ModelCollab, que es otra cosa— mientras el backend generado servía en {@code /api}. Los dos
 * proyectos compilaban, los dos analizaban limpio y cada uno era correcto por separado. El
 * síntoma sólo habría aparecido con la app instalada en un teléfono, como un 404 que no dice
 * nada sobre su causa.
 *
 * <p>Es la misma familia que el {@code @ManyToOne(mappedBy=...)} y que el {@code ea_localid} de
 * los conectores: <b>una regla que dos lugares tienen que respetar y que nadie confronta</b>. La
 * regla ahora vive en un solo sitio; esto comprueba que siga viviendo ahí.
 */
class RutasCoincidenTest {

    /** {@code @RequestMapping("/api/citas")} en el controlador generado. */
    private static final Pattern EN_JAVA =
            Pattern.compile("@RequestMapping\\(\"/api/([a-z0-9-]+)\"\\)");

    /** {@code 'Cita': '/citas',} en el mapa del cliente Dart. */
    private static final Pattern EN_DART =
            Pattern.compile("'([^']+)': '/([a-z0-9-]+)',");

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), sanitizer);

    private final FlutterAppGeneratorService movil = new FlutterAppGeneratorService(
            new NluIntentGeneratorService(new SpanishNounInflector()),
            sanitizer,
            JacksonConfig.canonicalObjectMapper());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    @ParameterizedTest(name = "en {0} la app y el backend hablan de las mismas rutas")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("Toda ruta que usa la app existe en el backend generado, y al revés")
    void lasRutasCoinciden(String fixture) throws Exception {
        CanonicalModel modelo = leer(fixture);

        java.util.Set<String> delBackend = new java.util.TreeSet<>();
        for (String java : backend.generar(modelo, "com.empresa.app", "demo").values()) {
            Matcher coincidencia = EN_JAVA.matcher(java);
            while (coincidencia.find()) {
                delBackend.add(coincidencia.group(1));
            }
        }
        assertThat(delBackend)
                .as("el backend generado no expone ningún controlador: el test no prueba nada")
                .isNotEmpty();

        String dart = movil.generar(modelo, "X", null).get("lib/api/cliente_api.dart");
        Map<String, String> deLaApp = new LinkedHashMap<>();
        Matcher enDart = EN_DART.matcher(dart);
        while (enDart.find()) {
            deLaApp.put(enDart.group(1), enDart.group(2));
        }
        assertThat(deLaApp)
                .as("la app generada no declara ninguna ruta: el test no prueba nada")
                .isNotEmpty();

        // Las dos direcciones. Que la app no invente una ruta es lo urgente; que no le falte
        // ninguna es lo que asegura que se pueda operar el negocio entero por voz, que es el
        // caso de uso.
        assertThat(deLaApp.values())
                .as("la app le pega a rutas que el backend generado no expone")
                .allMatch(delBackend::contains);
        assertThat(delBackend)
                .as("el backend expone entidades que la app no sabe usar")
                .allMatch(ruta -> deLaApp.containsValue(ruta));
    }

    @ParameterizedTest(name = "el prefijo de la app es el del backend en {0}")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
    })
    @DisplayName("El prefijo también: /api y no /api/v1")
    void elPrefijoCoincide(String fixture) throws Exception {
        CanonicalModel modelo = leer(fixture);

        // Ésta es exactamente la comprobación que faltaba. /api/v1 es el prefijo del backend de
        // ModelCollab —el que sirve el lienzo—, no el del backend que ModelCollab genera. Son
        // dos servidores distintos y el parecido es justo lo que hace fácil confundirlos.
        String config = movil.generar(modelo, "X", null).get("lib/config.dart");
        assertThat(config).contains("rutaBase = '/api'");

        // Se miran sólo las líneas de CÓDIGO. El comentario de config.dart nombra /api/v1 a
        // propósito, para explicar que el prefijo del backend de ModelCollab no es el del
        // backend que ModelCollab genera — y un test que prohíba mencionar el error obliga a
        // borrar justo la explicación que lo evita.
        List<String> codigo = config.lines()
                .filter(linea -> !linea.strip().startsWith("//"))
                .toList();
        assertThat(codigo)
                .as("hay código de la app apuntando a /api/v1")
                .noneMatch(linea -> linea.contains("/api/v1"));

        assertThat(backend.generar(modelo, "com.empresa.app", "demo").values())
                .anyMatch(java -> java.contains("@RequestMapping(\"/api/"));
    }
}
