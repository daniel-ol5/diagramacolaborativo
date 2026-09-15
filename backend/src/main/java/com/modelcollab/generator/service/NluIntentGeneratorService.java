package com.modelcollab.generator.service;

import com.modelcollab.generator.dto.Intent;
import com.modelcollab.generator.dto.IntentCatalog;
import com.modelcollab.generator.dto.IntentSlot;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.service.OwningSideRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * CU19: deriva el {@code intents.json} de la app móvil <b>desde el metamodelo</b> (§10.3, RF-08.3).
 *
 * <h2>La afirmación que este archivo tiene que sostener</h2>
 *
 * <p>Que el catálogo de la barbería y el de la veterinaria salgan <b>distintos sin tocar una
 * línea de código</b>. Si hubiera que retocar algo entre un dominio y otro, esto no sería
 * generación: sería una plantilla con relleno. Por eso no hay ni un nombre de entidad, ni un
 * verbo de negocio, ni un caso especial escrito acá dentro.
 *
 * <p>Eso tiene una consecuencia que conviene asumir de frente. §10.3 muestra, como ejemplo,
 * {@code "agenda una cita"} entre los disparadores de CREAR_CITA. <b>Ese disparador no se
 * genera</b>, y no es un olvido: "agendar" es lo que se hace con una cita y no con un pago, y esa
 * información no está en el metamodelo. Generarla para todas las entidades daría "agenda un
 * pago"; generarla sólo para Cita sería escribir el dominio adentro del generador, que es
 * exactamente lo que el criterio de aceptación prohíbe. Se emiten los verbos que valen para
 * cualquier entidad y se acepta la pérdida.
 *
 * <h2>Qué mira de cada entidad</h2>
 *
 * <ul>
 *   <li><b>Sus atributos, y los que hereda.</b> {@code Dueno} no declara ningún atributo propio
 *       de identidad: nombres, apellidos y teléfono los hereda de {@code Persona}. Sin recorrer
 *       la herencia, CREAR_DUENO no tendría ninguna ranura con la que crear un dueño.</li>
 *   <li><b>Sus relaciones escalares.</b> La ranura {@code cliente} del ejemplo de §10.3 no es un
 *       atributo de Cita: viene de la asociación con Cliente. Se decide con la misma regla D-08
 *       que usa el generador de código, así que la ranura aparece exactamente donde va la clave
 *       foránea.</li>
 * </ul>
 *
 * <h2>Lo que deliberadamente no genera</h2>
 *
 * <ul>
 *   <li><b>Las clases abstractas no tienen intenciones.</b> {@code Persona} es abstracta: no se
 *       crea una Persona, se crea un Dueno o un Veterinario. Emitir CREAR_PERSONA daría una
 *       orden que el backend generado rechaza.</li>
 *   <li><b>Las relaciones muchos a muchos no dan ranura.</b> Una cita tiene varios servicios, y
 *       dictar una lista no es una ranura: es una conversación con idas y vueltas. Queda para
 *       la pantalla.</li>
 *   <li><b>La clave primaria no es ranura.</b> Nadie dicta un UUID.</li>
 * </ul>
 */
@Service
public class NluIntentGeneratorService {

    /**
     * Verbos que valen para cualquier entidad. Ninguno es de un dominio en particular.
     *
     * <p>Van las formas de tu y de vos de cada uno ("crea" y "crea" con tilde en la a): el
     * reconocedor transcribe lo que oye, y la misma persona dice las dos segun a quien le hable.
     * Cuesta una linea por verbo y cubre a la mitad de los hablantes.
     */
    private static final List<String> VERBOS_CREAR =
            List.of("crea", "creá", "crear", "registra", "registrá", "anota", "agrega");

    /**
     * Aperturas que son un ADJETIVO y no un verbo: concuerdan en genero y no llevan articulo.
     *
     * <p>Van aparte porque tienen otra forma de frase. Metido en la lista de verbos, "nuevo"
     * producia <b>"nuevo una cita"</b> — mal el genero y con un articulo que sobra. La forma que
     * usa una persona, y la que muestra §10.3, es "nueva cita".
     */
    private static final List<String> ADJETIVOS_CREAR = List.of("nuevo", "nueva");

    private static final List<String> VERBOS_LISTAR =
            List.of("muestra", "mostrá", "lista", "listá", "ver", "dame", "cuáles son");

    private static final List<String> VERBOS_BUSCAR =
            List.of("busca", "buscá", "buscar", "encuentra", "encontrá", "mostrame");

    private static final List<String> VERBOS_ELIMINAR =
            List.of("elimina", "eliminá", "eliminar", "borra", "borrá", "quita");

    /**
     * Tipos con los que tiene sentido identificar un registro hablando.
     *
     * <p>Se usan para buscar y para eliminar. Quedan afuera {@code TEXT} —nadie identifica algo
     * por su descripción larga—, {@code DECIMAL} y {@code BOOLEAN}: "borra la cita cuyo total es
     * 250" no es una orden que alguien dé.
     */
    private static final Set<AttributeType> TIPOS_IDENTIFICADORES = Set.of(
            AttributeType.VARCHAR, AttributeType.DATE, AttributeType.DATETIME,
            AttributeType.INTEGER, AttributeType.BIGINT, AttributeType.UUID);

    private final SpanishNounInflector palabras;

    public NluIntentGeneratorService(SpanishNounInflector palabras) {
        this.palabras = palabras;
    }

    public IntentCatalog generar(CanonicalModel modelo) {
        Map<String, ClassEntity> porId = new LinkedHashMap<>();
        modelo.classes().forEach(c -> porId.put(c.id(), c));

        List<Intent> intenciones = new ArrayList<>();
        for (ClassEntity clase : modelo.classes()) {
            if (Boolean.TRUE.equals(clase.isAbstract())) {
                continue;
            }
            List<IntentSlot> completas = ranuras(clase, modelo, porId);
            List<IntentSlot> identificadoras = completas.stream()
                    .filter(NluIntentGeneratorService::sirveParaIdentificar)
                    .map(IntentSlot::opcional)
                    .toList();

            intenciones.add(intencion(clase, "CREAR", "CREATE", VERBOS_CREAR, false, completas));
            intenciones.add(intencion(clase, "LISTAR", "LIST", VERBOS_LISTAR, true, List.of()));
            intenciones.add(
                    intencion(clase, "BUSCAR", "SEARCH", VERBOS_BUSCAR, false, identificadoras));
            intenciones.add(
                    intencion(clase, "ELIMINAR", "DELETE", VERBOS_ELIMINAR, false, identificadoras));
        }
        return new IntentCatalog(IntentCatalog.VERSION, "es", intenciones);
    }

    // ------------------------------------------------------------ intenciones

    private Intent intencion(
            ClassEntity clase,
            String prefijo,
            String accion,
            List<String> verbos,
            boolean enPlural,
            List<IntentSlot> ranuras) {

        String nombre = prefijo + "_" + palabras.comoIdentificador(clase.name());

        String sustantivo = enPlural ? palabras.plural(clase.name()) : palabras.enPalabras(clase.name());
        String articulo = enPlural
                ? palabras.articuloDefinidoPlural(clase.name())
                : (accion.equals("CREATE")
                        ? palabras.articuloIndefinido(clase.name())
                        : palabras.articuloDefinido(clase.name()));

        List<String> disparadores = new ArrayList<>();
        for (String verbo : verbos) {
            disparadores.add(verbo + " " + articulo + " " + sustantivo);
        }
        if (accion.equals("CREATE")) {
            // "nueva cita" / "nuevo pago": adjetivo concordado y sin articulo.
            String adjetivo = palabras.esFemenino(clase.name())
                    ? ADJETIVOS_CREAR.get(1) : ADJETIVOS_CREAR.get(0);
            disparadores.add(adjetivo + " " + sustantivo);
        }
        if (enPlural) {
            // El sustantivo suelto SOLO en plural, y solo para listar.
            //
            // En singular era ambiguo entre crear, buscar y eliminar — las tres intenciones de
            // la misma entidad lo emitian. Y la peligrosa es eliminar: decir "cita" a secas
            // podia resolver a borrar una. Un disparador que puede significar tres cosas no es
            // un disparador; es una moneda al aire, y una de las tres caras destruye datos.
            //
            // En plural no hay ambiguedad: listar es la unica que habla en plural.
            disparadores.add(sustantivo);
        }

        return new Intent(nombre, clase.name(), accion, disparadores, ranuras);
    }

    // --------------------------------------------------------------- ranuras

    private List<IntentSlot> ranuras(
            ClassEntity clase, CanonicalModel modelo, Map<String, ClassEntity> porId) {

        List<IntentSlot> ranuras = new ArrayList<>();
        Set<String> yaPuestas = new HashSet<>();

        // Primero las propias y las heredadas, de la clase hacia arriba: si una clase hija
        // redefine un atributo del padre, gana la de la hija.
        for (ClassEntity nivel : conAncestros(clase, modelo, porId)) {
            for (Attribute atributo : nivel.attributes()) {
                if (Boolean.TRUE.equals(atributo.isPrimaryKey())) {
                    continue;
                }
                if (!yaPuestas.add(atributo.name().toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                ranuras.add(IntentSlot.deAtributo(
                        atributo.name(),
                        atributo.type().name(),
                        !Boolean.TRUE.equals(atributo.isNullable())));
            }
        }

        // Y después las referencias. Van al final porque en una frase dictada lo accesorio va
        // al final: "crea una cita el lunes a las 10 con Carlos".
        for (Relationship r : modelo.relationships()) {
            referencia(r, clase, porId).ifPresent(ranura -> {
                if (yaPuestas.add(ranura.name().toLowerCase(java.util.Locale.ROOT))) {
                    ranuras.add(ranura);
                }
            });
        }
        return ranuras;
    }

    /**
     * La ranura que le toca a esta clase por una relación, si le toca alguna.
     *
     * <p>Sólo la lleva <b>el lado que guarda la clave foránea</b>, y se decide con la misma regla
     * D-08 que usa el generador de código. Que salga del mismo cálculo no es prolijidad: es lo
     * que hace que la ranura exista exactamente donde el backend generado espera recibir el
     * identificador. Si se decidiera por separado, un día la app mandaría {@code clienteId} a una
     * entidad que no tiene esa columna.
     */
    private java.util.Optional<IntentSlot> referencia(
            Relationship r, ClassEntity clase, Map<String, ClassEntity> porId) {

        if (r.type() == RelationshipType.INHERITANCE
                || r.type() == RelationshipType.DEPENDENCY
                || r.type() == RelationshipType.MANY_TO_MANY) {
            return java.util.Optional.empty();
        }
        boolean soyOrigen = r.sourceClassId().equals(clase.id());
        boolean soyDestino = r.targetClassId().equals(clase.id());
        if (!soyOrigen && !soyDestino) {
            return java.util.Optional.empty();
        }
        if (!porId.containsKey(r.sourceClassId()) || !porId.containsKey(r.targetClassId())) {
            return java.util.Optional.empty();
        }

        boolean duenioEsOrigen = OwningSideRule.efectivo(r) == OwningSide.SOURCE;
        if (soyOrigen != duenioEsOrigen) {
            return java.util.Optional.empty(); // la clave foránea vive del otro lado
        }

        ClassEntity otro = porId.get(soyOrigen ? r.targetClassId() : r.sourceClassId());
        Multiplicity extremoDelOtro = soyOrigen ? r.targetMultiplicity() : r.sourceMultiplicity();

        String nombre = enMinusculaInicial(otro.name());
        return java.util.Optional.of(
                IntentSlot.deRelacion(nombre, otro.name(), extremoDelOtro == Multiplicity.ONE_ONE));
    }

    /**
     * La clase y sus ancestros, de la más específica a la más general.
     *
     * <p>Se corta si hay un ciclo. El validador de CU13 rechaza la herencia circular, pero este
     * servicio puede recibir un diagrama que nadie validó, y colgarse recorriendo un ciclo sería
     * peor que devolver algo incompleto.
     */
    private List<ClassEntity> conAncestros(
            ClassEntity clase, CanonicalModel modelo, Map<String, ClassEntity> porId) {

        Map<String, String> padrePorHijo = new HashMap<>();
        for (Relationship r : modelo.relationships()) {
            if (r.type() == RelationshipType.INHERITANCE) {
                padrePorHijo.putIfAbsent(r.sourceClassId(), r.targetClassId());
            }
        }

        List<ClassEntity> cadena = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        ClassEntity actual = clase;
        while (actual != null && vistos.add(actual.id())) {
            cadena.add(actual);
            actual = porId.get(padrePorHijo.get(actual.id()));
        }
        return cadena;
    }

    private static boolean sirveParaIdentificar(IntentSlot ranura) {
        if (ranura.references() != null) {
            return true; // "busca las citas de Carlos" es un criterio perfectamente normal
        }
        try {
            return TIPOS_IDENTIFICADORES.contains(AttributeType.valueOf(ranura.type()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String enMinusculaInicial(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return "referencia";
        }
        String limpio = nombre.strip();
        return Character.toLowerCase(limpio.charAt(0)) + limpio.substring(1);
    }
}
