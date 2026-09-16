package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.dto.Intent;
import com.modelcollab.generator.dto.IntentCatalog;
import com.modelcollab.generator.dto.IntentSlot;
import com.modelcollab.generator.service.NluIntentGeneratorService;
import com.modelcollab.generator.service.SpanishNounInflector;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CU19: el {@code intents.json} sale del metamodelo (§10.3, RF-08.3).
 *
 * <h2>El criterio que hace la afirmación fuerte</h2>
 *
 * <p>La barbería y la veterinaria tienen que producir catálogos <b>distintos sin tocar una línea
 * de código</b>. Si hubiera que retocar algo entre un dominio y otro, esto no sería generación
 * sino una plantilla con relleno — y esa es exactamente la pregunta que un tribunal hace.
 *
 * <p>Los dos dominios no se eligieron por ser dos: la veterinaria tiene <b>herencia</b> y una
 * <b>clase abstracta</b>, y la barbería no tiene ninguna de las dos. Probar con dos dominios
 * parecidos no habría probado nada.
 */
class NluIntentGeneratorServiceTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final NluIntentGeneratorService generador =
            new NluIntentGeneratorService(new SpanishNounInflector(), new IdentifierSanitizer());

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    private IntentCatalog barberia() throws Exception {
        return generador.generar(leer("reference-diagrams/barberia.json"));
    }

    private IntentCatalog veterinaria() throws Exception {
        return generador.generar(leer("reference-diagrams/veterinaria.json"));
    }

    private static Intent buscar(IntentCatalog catalogo, String nombre) {
        return catalogo.intents().stream()
                .filter(i -> i.name().equals(nombre))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no se generó la intención " + nombre));
    }

    // ------------------------------------------- el criterio de aceptación

    @Test
    @DisplayName("Dos dominios distintos dan catálogos distintos, con el mismo código")
    void dosDominiosDosCatalogos() throws Exception {
        IntentCatalog barberia = barberia();
        IntentCatalog veterinaria = veterinaria();

        // Cuatro intenciones por entidad concreta. La barbería tiene 5 clases y ninguna
        // abstracta; la veterinaria tiene 6 y una abstracta.
        assertThat(barberia.intents()).hasSize(5 * 4);
        assertThat(veterinaria.intents()).hasSize(5 * 4);

        List<String> deBarberia = barberia.intents().stream().map(Intent::name).toList();
        List<String> deVeterinaria = veterinaria.intents().stream().map(Intent::name).toList();

        assertThat(deBarberia).contains("CREAR_CITA", "LISTAR_CITA", "BUSCAR_BARBERO");
        assertThat(deVeterinaria).contains("CREAR_MASCOTA", "LISTAR_CONSULTA", "ELIMINAR_DUENO");
        // Lo que hay que sostener: no comparten ni una intención.
        assertThat(deBarberia).doesNotContainAnyElementsOf(deVeterinaria);
    }

    @Test
    @DisplayName("Una clase abstracta NO genera intenciones: no se crea una Persona")
    void lasAbstractasNoTienenIntenciones() throws Exception {
        // Persona es abstracta y es el padre de Dueno y Veterinario. Emitir CREAR_PERSONA daría
        // una orden que el backend generado rechaza, porque esa tabla no recibe filas propias.
        assertThat(veterinaria().intents()).extracting(Intent::entity).doesNotContain("Persona");
    }

    // ----------------------------------------------------- las ranuras

    @Test
    @DisplayName("Las ranuras salen de los atributos, con su tipo y si son obligatorias")
    void ranurasDesdeAtributos() throws Exception {
        Intent crearCita = buscar(barberia(), "CREAR_CITA");

        assertThat(crearCita.slots()).extracting(IntentSlot::name)
                .contains("fechaHora", "estado", "observaciones", "total");

        IntentSlot fechaHora = crearCita.slots().stream()
                .filter(s -> s.name().equals("fechaHora")).findFirst().orElseThrow();
        // El tipo es lo que le dice a la app qué analizador local usar: DATETIME resuelve
        // "mañana a las 10".
        assertThat(fechaHora.type()).isEqualTo("DATETIME");
        assertThat(fechaHora.required()).isTrue();

        IntentSlot observaciones = crearCita.slots().stream()
                .filter(s -> s.name().equals("observaciones")).findFirst().orElseThrow();
        // isNullable = true en el diagrama, así que la ranura es opcional.
        assertThat(observaciones.required()).isFalse();
    }

    @Test
    @DisplayName("La clave primaria no es una ranura: nadie dicta un UUID")
    void laClavePrimariaNoEsRanura() throws Exception {
        for (Intent intencion : barberia().intents()) {
            assertThat(intencion.slots()).extracting(IntentSlot::name).doesNotContain("id");
        }
    }

    @Test
    @DisplayName("Una relación da ranura, y del lado donde vive la clave foránea")
    void ranurasDesdeRelaciones() throws Exception {
        // Es el ejemplo de §10.3: la ranura "cliente" de "agenda una cita con Carlos" NO es un
        // atributo de Cita. Existe porque hay una asociación Cliente 1..1 -> Cita 0..*.
        Intent crearCita = buscar(barberia(), "CREAR_CITA");

        IntentSlot cliente = crearCita.slots().stream()
                .filter(s -> s.name().equals("cliente")).findFirst().orElseThrow();
        assertThat(cliente.references()).isEqualTo("Cliente");
        // VARCHAR porque lo que se dicta es un nombre; la app lo resuelve contra la entidad.
        assertThat(cliente.type()).isEqualTo("VARCHAR");

        // Y el lado de la colección NO la lleva: un Cliente no se crea dictando sus citas.
        Intent crearCliente = buscar(barberia(), "CREAR_CLIENTE");
        assertThat(crearCliente.slots()).extracting(IntentSlot::references).doesNotContain("Cita");
    }

    @Test
    @DisplayName("Una relación muchos a muchos no da ranura: dictar una lista no es una ranura")
    void muchosAMuchosNoDaRanura() throws Exception {
        // Cita <-> Servicio es MANY_TO_MANY. "Una cita con corte, barba y lavado" es una
        // conversación con idas y vueltas, no un hueco que se rellena de una.
        Intent crearCita = buscar(barberia(), "CREAR_CITA");

        assertThat(crearCita.slots()).extracting(IntentSlot::references).doesNotContain("Servicio");
    }

    @Test
    @DisplayName("Los atributos HEREDADOS entran: sin eso no se puede crear un Dueno")
    void losAtributosHeredadosEntran() throws Exception {
        // Dueno sólo declara direccion, email y fechaAlta. Nombres, apellidos, ci y telefono los
        // hereda de Persona — y son justamente los que uno dictaría. Sin recorrer la herencia,
        // CREAR_DUENO no tendría con qué crear un dueño.
        Intent crearDueno = buscar(veterinaria(), "CREAR_DUENO");

        assertThat(crearDueno.slots()).extracting(IntentSlot::name)
                .contains("direccion", "email", "fechaAlta")
                .contains("nombres", "apellidos", "ci", "telefono");
    }

    @Test
    @DisplayName("Buscar y eliminar dejan afuera lo que no sirve para identificar")
    void buscarYEliminarFiltran() throws Exception {
        Intent buscarCita = buscar(barberia(), "BUSCAR_CITA");

        // "Busca la cita cuyas observaciones son..." no es una orden que alguien dé. TEXT y
        // DECIMAL quedan afuera; fechaHora y estado sirven.
        assertThat(buscarCita.slots()).extracting(IntentSlot::name)
                .contains("fechaHora", "estado")
                .doesNotContain("observaciones", "total");

        // Y todas opcionales: cualquiera de ellas alcanza como criterio.
        assertThat(buscarCita.slots()).allMatch(s -> !s.required());
    }

    @Test
    @DisplayName("Listar no lleva ranuras")
    void listarNoLlevaRanuras() throws Exception {
        assertThat(buscar(barberia(), "LISTAR_SERVICIO").slots()).isEmpty();
    }

    // ------------------------------------------------- las frases dictadas

    @Test
    @DisplayName("Las frases concuerdan en género y número")
    void lasFrasesEstanBienDichas() throws Exception {
        // Una app que le dice "muestra las pagos" al usuario se nota en la demostración más que
        // un error de fondo.
        assertThat(buscar(barberia(), "CREAR_CITA").triggers()).contains("crea una cita");
        assertThat(buscar(barberia(), "CREAR_PAGO").triggers()).contains("crea un pago");
        assertThat(buscar(barberia(), "LISTAR_CITA").triggers()).contains("muestra las citas");
        assertThat(buscar(barberia(), "LISTAR_PAGO").triggers()).contains("muestra los pagos");
        assertThat(buscar(veterinaria(), "LISTAR_MASCOTA").triggers())
                .contains("muestra las mascotas");
    }

    @Test
    @DisplayName("Cada intención tiene varias frases: el reconocedor devuelve lo que entendió")
    void variasFrasesPorIntencion() throws Exception {
        // Con una sola frase exacta la app no responde nunca: el reconocimiento de voz no
        // devuelve lo que se dijo sino lo que entendió.
        for (Intent intencion : barberia().intents()) {
            assertThat(intencion.triggers())
                    .as("la intención %s tiene muy pocas frases", intencion.name())
                    .hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    @DisplayName("NINGUNA frase dispara dos intenciones distintas")
    void ningunaFraseEsAmbigua() throws Exception {
        // Este es el invariante que sostiene todo lo demas. La primera version emitia el
        // sustantivo suelto —"cita"— en crear, buscar Y eliminar, o sea que decir una palabra
        // podia resolver a cualquiera de las tres. Un disparador que significa tres cosas no es
        // un disparador; es una moneda al aire, y una de las tres caras BORRA datos.
        for (String fixture : List.of("reference-diagrams/barberia.json",
                "reference-diagrams/veterinaria.json",
                "reference-diagrams/inventario.json")) {

            java.util.Map<String, List<String>> donde = new java.util.HashMap<>();
            for (Intent intencion : generador.generar(leer(fixture)).intents()) {
                for (String frase : intencion.triggers()) {
                    donde.computeIfAbsent(frase, f -> new java.util.ArrayList<>())
                            .add(intencion.name());
                }
            }
            java.util.Map<String, List<String>> ambiguas = donde.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1)
                    .collect(java.util.stream.Collectors.toMap(
                            java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));

            assertThat(ambiguas)
                    .as("en %s hay frases que disparan mas de una intencion", fixture)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("Las frases con adjetivo concuerdan: 'nueva cita', no 'nuevo una cita'")
    void elAdjetivoConcuerda() throws Exception {
        // "nuevo" es un adjetivo, no un verbo: no lleva articulo y concuerda en genero. En la
        // lista de verbos producia "nuevo una cita", mal las dos cosas.
        assertThat(buscar(barberia(), "CREAR_CITA").triggers())
                .contains("nueva cita")
                .doesNotContain("nuevo una cita", "nueva una cita");
        assertThat(buscar(barberia(), "CREAR_PAGO").triggers()).contains("nuevo pago");
    }

    @Test
    @DisplayName("El catálogo NO fija un dialecto: el spike mostró por qué")
    void noFijaDialecto() throws Exception {
        // El listado de idiomas del reconocedor incluye los que no tienen modelo descargado, así
        // que fijar "es-ES" o "es-US" sería elegir por el aparato equivocado. La app elige por
        // modelo instalado.
        assertThat(barberia().language()).isEqualTo("es");
    }

    // ------------------------------------------------------ el archivo

    @Test
    @DisplayName("Serializa a un JSON con la forma de §10.3")
    void serializaComoMandaLaEspecificacion() throws Exception {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(barberia());

        assertThat(json).contains("\"intents\"").contains("\"CREAR_CITA\"")
                .contains("\"action\" : \"CREATE\"").contains("\"triggers\"").contains("\"slots\"");
        // El mapper canónico va con NON_NULL, así que una ranura que no referencia a nadie no
        // arrastra un "references": null por todo el archivo.
        assertThat(json).doesNotContain("\"references\" : null");
    }

    @Test
    @DisplayName("Un diagrama vacío da un catálogo vacío, no una excepción")
    void diagramaVacio() {
        assertThat(generador.generar(CanonicalModel.empty()).intents()).isEmpty();
    }
}
