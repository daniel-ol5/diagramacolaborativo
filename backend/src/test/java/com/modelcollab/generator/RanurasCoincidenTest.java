package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.dto.Intent;
import com.modelcollab.generator.dto.IntentSlot;
import com.modelcollab.generator.service.NluIntentGeneratorService;
import com.modelcollab.generator.service.SpanishNounInflector;
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
 * Las ranuras que la app rellena por voz tienen que ser <b>los campos que el backend acepta</b>.
 *
 * <h2>El par que este archivo confronta</h2>
 *
 * <p>El backend generado expone un {@code RequestDTO} por entidad; la app genera un catálogo de
 * intenciones con sus ranuras. Nada obliga a que coincidan salvo que alguien lo compruebe — y
 * <b>no coincidían</b>.
 *
 * <p>El DTO de {@code Cita} pedía {@code clienteId} (un UUID) y la ranura se llamaba
 * {@code cliente} (un nombre). O sea que <i>"crea una cita mañana a las diez con Carlos"</i>
 * habría fallado <b>siempre</b>: el servidor descartaba el campo desconocido y rechazaba la
 * petición por el obligatorio que faltaba. Los dos proyectos compilaban, los dos analizaban
 * limpio, y cada uno era correcto por separado.
 *
 * <p>Y había un segundo desacuerdo escondido debajo: la app derivaba el nombre del campo por su
 * cuenta e <b>ignoraba el rol de la relación</b>. Con {@code targetRole} "titular", el backend
 * espera {@code titularId} y la app decía {@code cliente}.
 *
 * <h2>Las dos direcciones importan, y por motivos distintos</h2>
 *
 * <ul>
 *   <li><b>Ranura sin campo</b>: la app manda algo que el servidor descarta. Silencioso.</li>
 *   <li><b>Campo obligatorio sin ranura</b>: el usuario dicta una orden completa y el servidor
 *       la rechaza, <b>sin que la app pueda siquiera pedirle el dato que falta</b>. Es una
 *       intención imposible de completar por voz.</li>
 * </ul>
 *
 * <p>Un campo <b>opcional</b> sin ranura no es un fallo: no todo se dicta.
 */
class RanurasCoincidenTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), sanitizer);

    private final NluIntentGeneratorService nlu =
            new NluIntentGeneratorService(new SpanishNounInflector(), sanitizer);

    /** Un campo del RequestDTO generado. */
    private record Campo(String nombre, boolean obligatorio) {}

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    /**
     * Los campos de cada {@code RequestDTO}, leídos del Java generado.
     *
     * <p>Se recorre línea por línea en vez de con una expresión regular grande: las anotaciones
     * van en sus propias líneas y lo que dice si un campo es obligatorio es <b>la anotación
     * anterior</b>, no algo que esté en la línea del campo.
     */
    private Map<String, List<Campo>> camposDeLosDto(CanonicalModel modelo) {
        Map<String, List<Campo>> porEntidad = new LinkedHashMap<>();

        for (Map.Entry<String, String> archivo
                : backend.generar(modelo, "com.empresa.app", "demo").entrySet()) {

            if (!archivo.getKey().endsWith("RequestDTO.java")) {
                continue;
            }
            String nombreDeArchivo =
                    archivo.getKey().substring(archivo.getKey().lastIndexOf('/') + 1);
            String entidad = nombreDeArchivo.replace("RequestDTO.java", "");

            List<Campo> campos = new ArrayList<>();
            boolean dentro = false;
            boolean obligatorioElQueViene = false;

            for (String linea : archivo.getValue().split("\n")) {
                String limpia = linea.strip();
                if (limpia.startsWith("public record")) {
                    dentro = true;
                    continue;
                }
                if (!dentro || limpia.startsWith(") {")) {
                    continue;
                }
                if (limpia.startsWith("@")) {
                    obligatorioElQueViene |= limpia.contains("NotNull") || limpia.contains("NotBlank");
                    continue;
                }
                String[] partes = limpia.replace(",", "").split("\\s+");
                if (partes.length == 2) {
                    campos.add(new Campo(partes[1], obligatorioElQueViene));
                }
                obligatorioElQueViene = false;
            }
            porEntidad.put(entidad, campos);
        }
        return porEntidad;
    }

    @ParameterizedTest(name = "en {0} la app rellena campos que el backend acepta")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("Toda ranura de CREAR corresponde a un campo real del RequestDTO")
    void todaRanuraEsUnCampo(String fixture) throws Exception {
        CanonicalModel modelo = leer(fixture);
        Map<String, List<Campo>> dto = camposDeLosDto(modelo);

        assertThat(dto).as("el backend no generó ningún DTO: el test no prueba nada").isNotEmpty();

        List<String> huerfanas = new ArrayList<>();
        for (Intent intencion : nlu.generar(modelo).intents()) {
            if (!"CREATE".equals(intencion.action())) {
                continue;
            }
            List<Campo> campos = dto.get(intencion.entity());
            assertThat(campos)
                    .as("la app genera intenciones para %s y el backend no le da DTO",
                            intencion.entity())
                    .isNotNull();

            Set<String> nombres = new LinkedHashSet<>();
            campos.forEach(c -> nombres.add(c.nombre()));

            for (IntentSlot ranura : intencion.slots()) {
                if (!nombres.contains(ranura.field())) {
                    huerfanas.add(intencion.entity() + "." + ranura.field()
                            + " (ranura '" + ranura.name() + "') no existe en el DTO " + nombres);
                }
            }
        }
        // Una ranura sin campo es silenciosa: el servidor descarta el dato y rechaza la
        // peticion por otro motivo, asi que el mensaje de error ni siquiera la nombra.
        assertThat(huerfanas).as("la app manda campos que el backend descarta").isEmpty();
    }

    @ParameterizedTest(name = "en {0} todo campo obligatorio se puede dictar")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("Todo campo OBLIGATORIO del DTO tiene una ranura que lo rellene")
    void todoCampoObligatorioSePuedeDictar(String fixture) throws Exception {
        CanonicalModel modelo = leer(fixture);
        Map<String, List<Campo>> dto = camposDeLosDto(modelo);

        Map<String, Set<String>> ranurasPorEntidad = new LinkedHashMap<>();
        for (Intent intencion : nlu.generar(modelo).intents()) {
            if ("CREATE".equals(intencion.action())) {
                Set<String> campos = new LinkedHashSet<>();
                intencion.slots().forEach(r -> campos.add(r.field()));
                ranurasPorEntidad.put(intencion.entity(), campos);
            }
        }

        List<String> imposibles = new ArrayList<>();
        for (Map.Entry<String, List<Campo>> entidad : dto.entrySet()) {
            Set<String> ranuras =
                    ranurasPorEntidad.getOrDefault(entidad.getKey(), Set.of());
            for (Campo campo : entidad.getValue()) {
                if (campo.obligatorio() && !ranuras.contains(campo.nombre())) {
                    imposibles.add(entidad.getKey() + "." + campo.nombre());
                }
            }
        }
        // Un campo OPCIONAL sin ranura esta bien: no todo se dicta. Uno obligatorio sin ranura
        // convierte a esa intencion en imposible de completar por voz — el usuario dicta la
        // orden entera y el servidor la rechaza, sin que la app pueda pedirle lo que falta.
        assertThat(imposibles)
                .as("hay campos obligatorios que ninguna ranura puede rellenar: "
                        + "esas intenciones no se pueden completar dictando")
                .isEmpty();
    }

    @ParameterizedTest(name = "en {0} las referencias viajan con su clave de id")
    @ValueSource(strings = {
        "reference-diagrams/barberia.json",
        "reference-diagrams/veterinaria.json",
    })
    @DisplayName("Una ranura de referencia dice un nombre y viaja como identificador")
    void lasReferenciasViajanComoId(String fixture) throws Exception {
        CanonicalModel modelo = leer(fixture);

        boolean huboAlguna = false;
        for (Intent intencion : nlu.generar(modelo).intents()) {
            for (IntentSlot ranura : intencion.slots()) {
                if (ranura.references() == null) {
                    // En un atributo el nombre hablado y la clave del cable son el mismo.
                    assertThat(ranura.field()).isEqualTo(ranura.name());
                    continue;
                }
                huboAlguna = true;
                // Lo que se dice no es lo que viaja: "cliente" contra "clienteId".
                assertThat(ranura.field())
                        .as("la referencia %s no viaja con clave de identificador", ranura.name())
                        .isEqualTo(ranura.name() + "Id");
            }
        }
        assertThat(huboAlguna)
                .as("el fixture tiene que traer relaciones para que esto signifique algo")
                .isTrue();
    }
}
