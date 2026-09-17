package com.modelcollab.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.FreeMarkerConfig;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.generator.dto.SchemaColumn;
import com.modelcollab.generator.dto.SchemaPreview;
import com.modelcollab.generator.dto.SchemaTable;
import com.modelcollab.generator.service.SchemaPreviewService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>La base de datos que se muestra en pantalla y la que se descarga son la misma.</b>
 *
 * <h2>El par que este archivo confronta</h2>
 *
 * <p>La vista Entidad-Relación dibuja lo que devuelve {@code GET .../schema-preview}; el usuario
 * se lleva un ZIP cuyas entidades JPA declaran los nombres físicos de verdad. <b>Son dos salidas
 * que el usuario puede poner una al lado de la otra</b>, y eso las vuelve peligrosas: si la vista
 * dijera {@code movimiento_stock} y el DDL creara {@code movimientostock}, no hace falta ser
 * desarrollador para verlo. Cualquiera que compare la pantalla con el ZIP lo nota, un tribunal
 * incluido.
 *
 * <h2>Por qué sigue haciendo falta si comparten el cálculo</h2>
 *
 * <p>{@code SchemaPreviewService} no reimplementa nada: lee el mismo {@code ModeloDeGeneracion}
 * que renderizan las plantillas. Eso elimina la divergencia entre <i>dos cuentas</i> — que es la
 * que nos costó el {@code @ManyToOne(mappedBy)}, el prefijo de ruta del móvil y la
 * correspondencia ranura/DTO.
 *
 * <p>Pero <b>no elimina la que hay entre el modelo y quien lo consume</b>. Si una plantilla
 * ignora un campo del modelo —deja de emitir un {@code @JoinColumn}, se olvida del
 * {@code @JoinTable}, escribe el nombre a mano— el modelo sigue estando bien y el ZIP sale mal.
 * Por eso esto no compara contra el modelo: compara contra el <b>Java realmente renderizado</b>,
 * que es lo que el usuario descarga.
 *
 * <h2>La única regla de JPA que el test rehace</h2>
 *
 * <p>Con la estrategia {@code JOINED}, la tabla de una subclase lleva una columna de clave que
 * <b>el Java no declara</b>: la crea Hibernate con el nombre de la clave de la raíz. El test la
 * agrega del lado del Java subiendo por los {@code extends} del <i>código generado</i>, no
 * recalculando ningún nombre. Es una propiedad de JPA, no una decisión nuestra.
 */
class VistaPreviaCoincideConElZipTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    private final SpringBootGeneratorService backend = new SpringBootGeneratorService(
            new FreeMarkerConfig().freemarkerConfiguration(), sanitizer);

    private final SchemaPreviewService vistaPrevia = new SchemaPreviewService(sanitizer);

    /** Un nombre físico leído del Java generado, con si venía entrecomillado. */
    private record Fisico(String nombre, boolean entrecomillado) {

        @Override
        public String toString() {
            return entrecomillado ? "\"" + nombre + "\"" : nombre;
        }
    }

    /** Una tabla tal como la declara el Java generado. */
    private record TablaDelJava(Fisico tabla, String extiende, List<Fisico> columnas) {}

    @ParameterizedTest(name = "en {0} la vista previa dice lo mismo que el ZIP")
    @ValueSource(strings = {
        "edge-cases.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/barberia.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("Toda tabla y toda columna de la vista previa existe igual en el Java generado")
    void laVistaPreviaEsElEsquemaQueSeDescarga(String fixture) throws Exception {
        CanonicalModel modelo = leer(fixture);

        SchemaPreview previa = vistaPrevia.calcular(modelo, 7L);
        Map<String, TablaDelJava> delJava = tablasDelJava(
                backend.generar(modelo, "com.empresa.app", "demo"));

        assertThat(previa.tables()).as("la vista previa salió vacía: el test no prueba nada")
                .isNotEmpty();
        assertThat(delJava).as("no se generó ninguna entidad: el test no prueba nada").isNotEmpty();

        // --- las tablas ---------------------------------------------------------------------
        Set<String> enLaVista = new LinkedHashSet<>();
        previa.tables().forEach(t -> enLaVista.add(conComillas(t.name(), t.quoted())));

        Set<String> enElZip = new LinkedHashSet<>();
        delJava.values().forEach(t -> enElZip.add(t.tabla().toString()));

        assertThat(enLaVista)
                .as("la vista previa muestra tablas que el ZIP no crea, o al revés")
                .containsExactlyInAnyOrderElementsOf(enElZip);

        // --- las columnas, tabla por tabla --------------------------------------------------
        List<String> diferencias = new ArrayList<>();
        for (SchemaTable tabla : previa.tables()) {
            TablaDelJava enJava = delJava.get(conComillas(tabla.name(), tabla.quoted()));
            if (enJava == null) {
                continue; // ya lo dijo la comparación de arriba
            }
            Set<String> vista = new LinkedHashSet<>();
            tabla.columns().forEach(c -> vista.add(conComillas(c.name(), c.quoted())));

            Set<String> zip = new LinkedHashSet<>();
            enJava.columnas().forEach(c -> zip.add(c.toString()));
            // La clave que Hibernate le pone sola a la tabla de una subclase.
            claveHeredada(enJava, delJava).ifPresent(zip::add);

            if (!vista.equals(zip)) {
                diferencias.add("tabla " + tabla.name()
                        + ": la vista dice " + vista + " y el ZIP crea " + zip);
            }
        }
        assertThat(diferencias)
                .as("la vista Entidad-Relación y el ZIP describen bases de datos distintas")
                .isEmpty();
    }

    @ParameterizedTest(name = "en {0} las claves foráneas apuntan a tablas que existen")
    @ValueSource(strings = {
        "edge-cases.json",
        "reference-diagrams/veterinaria.json",
        "reference-diagrams/barberia.json",
        "reference-diagrams/inventario.json",
    })
    @DisplayName("Toda referencia de la vista previa apunta a una tabla de la misma vista")
    void lasReferenciasApuntanAAlgoQueExiste(String fixture) throws Exception {
        SchemaPreview previa = vistaPrevia.calcular(leer(fixture), 1L);

        Set<String> tablas = new LinkedHashSet<>();
        previa.tables().forEach(t -> tablas.add(t.name()));

        List<String> colgadas = new ArrayList<>();
        boolean huboAlguna = false;
        for (SchemaTable tabla : previa.tables()) {
            for (SchemaColumn columna : tabla.columns()) {
                if (columna.references() == null) {
                    continue;
                }
                huboAlguna = true;
                if (!tablas.contains(columna.references())) {
                    colgadas.add(tabla.name() + "." + columna.name()
                            + " -> " + columna.references());
                }
                assertThat(columna.type())
                        .as("la clave foránea %s.%s viaja sin tipo: el cliente no puede dibujarla",
                                tabla.name(), columna.name())
                        .isNotNull();
            }
        }
        // El cliente dibuja las lineas de la vista ER usando references. Una que apunte a una
        // tabla que no esta en la respuesta es una linea que no se puede dibujar.
        assertThat(colgadas).as("hay claves foráneas que apuntan a tablas inexistentes").isEmpty();
        assertThat(huboAlguna)
                .as("el fixture tiene que traer relaciones para que esto signifique algo")
                .isTrue();
    }

    // ------------------------------------------------- leer el Java generado

    private static final Pattern TABLA = Pattern.compile(
            "@(?:[\\w.]+\\.)?Table\\(name = \"((?:\\\\.|[^\"\\\\])*)\"\\)");
    private static final Pattern COLUMNA = Pattern.compile(
            "@(?:[\\w.]+\\.)?(?:Column|JoinColumn)\\(name = \"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NOMBRE = Pattern.compile(
            "name = \"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * Las tablas que el Java generado declara, incluidas las intermedias de los
     * {@code @JoinTable}.
     *
     * <p>Se lee línea por línea porque un {@code @JoinTable} ocupa cuatro y sus dos
     * {@code @JoinColumn} <b>no son columnas de esta tabla</b>: pertenecen a la intermedia. Un
     * parseo ingenuo las contaría dos veces y el test daría verde sobre un ZIP equivocado.
     */
    private Map<String, TablaDelJava> tablasDelJava(Map<String, String> generado) {
        Map<String, TablaDelJava> tablas = new LinkedHashMap<>();

        for (Map.Entry<String, String> archivo : generado.entrySet()) {
            if (!archivo.getKey().contains("/domain/model/")) {
                continue;
            }
            String java = archivo.getValue();

            Matcher tabla = TABLA.matcher(java);
            assertThat(tabla.find())
                    .as("la entidad %s salió sin @Table", archivo.getKey())
                    .isTrue();
            Fisico nombreDeTabla = fisico(tabla.group(1));

            String extiende = null;
            int posicion = java.indexOf(" extends ");
            if (posicion > 0) {
                extiende = java.substring(posicion + " extends ".length()).split("[\\s{]")[0];
            }

            List<Fisico> columnas = new ArrayList<>();
            String[] lineas = java.split("\n");
            for (int i = 0; i < lineas.length; i++) {
                String linea = lineas[i];

                if (linea.contains("JoinTable(")) {
                    i = intermedia(lineas, i, tablas);
                    continue;
                }
                Matcher columna = COLUMNA.matcher(linea);
                if (columna.find()) {
                    columnas.add(fisico(columna.group(1)));
                }
            }
            tablas.put(nombreDeTabla.toString(),
                    new TablaDelJava(nombreDeTabla, extiende, columnas));
        }
        return tablas;
    }

    /** Lee un bloque {@code @JoinTable} entero y devuelve la última línea consumida. */
    private static int intermedia(String[] lineas, int desde, Map<String, TablaDelJava> tablas) {
        List<String> nombres = new ArrayList<>();
        int i = desde;
        while (i < lineas.length && nombres.size() < 3) {
            Matcher nombre = NOMBRE.matcher(lineas[i]);
            while (nombre.find()) {
                nombres.add(nombre.group(1));
            }
            if (lineas[i].contains("))")) {
                break;
            }
            i++;
        }
        assertThat(nombres)
                .as("un @JoinTable salió sin sus tres nombres: %s", String.join(" | ", nombres))
                .hasSize(3);

        Fisico tabla = fisico(nombres.get(0));
        tablas.put(tabla.toString(), new TablaDelJava(
                tabla, null, List.of(fisico(nombres.get(1)), fisico(nombres.get(2)))));
        return i;
    }

    /**
     * La columna de clave que Hibernate le agrega a la tabla de una subclase con {@code JOINED}.
     *
     * <p>Se saca del Java del ancestro —de su {@code @Id}, que ahí sí está escrito— y no de
     * ninguna regla de nombres: lo único que este método sabe es que una subclase hereda la clave
     * de su raíz, que es JPA y no una decisión del generador.
     */
    private static java.util.Optional<String> claveHeredada(
            TablaDelJava tabla, Map<String, TablaDelJava> todas) {

        if (tabla.extiende() == null) {
            return java.util.Optional.empty();
        }
        TablaDelJava ancestro = todas.values().stream()
                .filter(t -> t.tabla().nombre().equals(enSnake(tabla.extiende()))
                        || t.tabla().toString().contains(enSnake(tabla.extiende())))
                .findFirst()
                .orElse(null);
        // La primera columna de una entidad raíz es su @Id: es lo primero que escribe la
        // plantilla, antes de los atributos.
        while (ancestro != null && ancestro.extiende() != null) {
            String siguiente = ancestro.extiende();
            ancestro = todas.values().stream()
                    .filter(t -> t.tabla().nombre().equals(enSnake(siguiente)))
                    .findFirst()
                    .orElse(null);
        }
        return ancestro == null || ancestro.columnas().isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(ancestro.columnas().get(0).toString());
    }

    /** Sólo para emparejar una clase con su tabla; no decide ningún nombre físico. */
    private static String enSnake(String clase) {
        return clase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static Fisico fisico(String literal) {
        String limpio = literal.replace("\\\"", "\"").replace("\\\\", "\\");
        return new Fisico(limpio.replace("\"", ""), limpio.contains("\""));
    }

    private static String conComillas(String nombre, boolean entrecomillado) {
        return entrecomillado ? "\"" + nombre + "\"" : nombre;
    }

    private CanonicalModel leer(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta el fixture %s", recurso).isNotNull();
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }
}
