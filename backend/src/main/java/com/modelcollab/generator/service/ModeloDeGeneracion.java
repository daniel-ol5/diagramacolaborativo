package com.modelcollab.generator.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.OwningSideRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Traduce el modelo canónico de §7 a la estructura que consumen las plantillas FreeMarker.
 *
 * <p><b>Toda la decisión vive acá y ninguna en las plantillas.</b> Una plantilla con lógica es
 * imposible de probar por unidad y muy fácil de romper: no compila, no tiene tipos y el error
 * aparece recién al renderizar. Las plantillas sólo interpolan cadenas ya resueltas — el nombre
 * de la tabla ya viene escapado, el tipo Java ya está elegido, el {@code mappedBy} ya está
 * calculado.
 *
 * <p>Las estructuras son {@code Map} y no records porque FreeMarker accede a las propiedades por
 * convención de getters, y los accesores de un record no la siguen.
 */
class ModeloDeGeneracion {

    /** Correspondencia de §7 a tipos Java. Todos envueltos: una columna nullable necesita null. */
    private static final Map<AttributeType, String> TIPOS = Map.of(
            AttributeType.INTEGER, "Integer",
            AttributeType.BIGINT, "Long",
            AttributeType.VARCHAR, "String",
            AttributeType.TEXT, "String",
            AttributeType.DECIMAL, "BigDecimal",
            AttributeType.BOOLEAN, "Boolean",
            AttributeType.DATE, "LocalDate",
            AttributeType.DATETIME, "LocalDateTime",
            AttributeType.UUID, "UUID");

    /**
     * Todo nombre simple que el código generado puede necesitar, con su paquete.
     *
     * <p>Existe por una trampa concreta que {@code edge-cases.json} provoca a propósito: ese
     * diagrama tiene una clase llamada <b>Table</b>. Un {@code import jakarta.persistence.Table}
     * dentro de {@code Order.java} <b>ensombrece</b> a la entidad {@code Table} del mismo paquete
     * (JLS 6.4.1), con lo que la relación Order-Table deja de compilar; y sin ese import, el
     * {@code @Table} de la entidad se resuelve contra la clase del diagrama y tampoco compila.
     * No hay un orden de imports que salve los dos casos a la vez.
     *
     * <p>La salida es calcular las colisiones contra los nombres de clase del diagrama y usar el
     * nombre calificado sólo para las que chocan. Vale igual para {@code List}, {@code UUID} o
     * incluso {@code String}: nada impide que alguien dibuje una clase así.
     */
    private static final Map<String, String> REGISTRO_DE_TIPOS = Map.ofEntries(
            Map.entry("Entity", "jakarta.persistence.Entity"),
            Map.entry("Table", "jakarta.persistence.Table"),
            Map.entry("Id", "jakarta.persistence.Id"),
            Map.entry("GeneratedValue", "jakarta.persistence.GeneratedValue"),
            Map.entry("GenerationType", "jakarta.persistence.GenerationType"),
            Map.entry("Column", "jakarta.persistence.Column"),
            Map.entry("ManyToOne", "jakarta.persistence.ManyToOne"),
            Map.entry("OneToMany", "jakarta.persistence.OneToMany"),
            Map.entry("ManyToMany", "jakarta.persistence.ManyToMany"),
            Map.entry("OneToOne", "jakarta.persistence.OneToOne"),
            Map.entry("JoinColumn", "jakarta.persistence.JoinColumn"),
            Map.entry("JoinTable", "jakarta.persistence.JoinTable"),
            Map.entry("Inheritance", "jakarta.persistence.Inheritance"),
            Map.entry("InheritanceType", "jakarta.persistence.InheritanceType"),
            Map.entry("CascadeType", "jakarta.persistence.CascadeType"),
            Map.entry("List", "java.util.List"),
            Map.entry("ArrayList", "java.util.ArrayList"),
            Map.entry("UUID", "java.util.UUID"),
            Map.entry("BigDecimal", "java.math.BigDecimal"),
            Map.entry("LocalDate", "java.time.LocalDate"),
            Map.entry("LocalDateTime", "java.time.LocalDateTime"),
            Map.entry("String", "java.lang.String"),
            Map.entry("Long", "java.lang.Long"),
            Map.entry("Integer", "java.lang.Integer"),
            Map.entry("Boolean", "java.lang.Boolean"));

    /** Los de java.lang no se importan nunca; sólo se califican si el diagrama los pisa. */
    private static final Set<String> DE_JAVA_LANG =
            Set.of("String", "Long", "Integer", "Boolean");

    private final IdentifierSanitizer sanitizer;

    ModeloDeGeneracion(IdentifierSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /** Una entidad por clase, con todo resuelto y lista para renderizar. */
    List<Map<String, Object>> entidades(CanonicalModel modelo, String paquete) {
        Map<String, ClassEntity> porId = new HashMap<>();
        modelo.classes().forEach(c -> porId.put(c.id(), c));

        Map<String, String> nombresJava = new HashMap<>();
        modelo.classes().forEach(c -> nombresJava.put(c.id(), sanitizer.nombreDeClase(c.name())));

        // Resolución de nombres calculada una sola vez para todo el diagrama.
        Set<String> nombresDelDiagrama = new LinkedHashSet<>(nombresJava.values());
        Map<String, String> tipos = new LinkedHashMap<>();
        Set<String> importables = new LinkedHashSet<>();
        REGISTRO_DE_TIPOS.forEach((simple, calificado) -> {
            boolean choca = nombresDelDiagrama.contains(simple);
            tipos.put(simple, choca ? calificado : simple);
            if (!choca && !DE_JAVA_LANG.contains(simple)) {
                importables.add(calificado);
            }
        });

        // Que entidad tiene DTO. Las abstractas no: no se instancian ni se exponen por REST.
        Map<String, String> dtoDeEntidad = new LinkedHashMap<>();
        for (ClassEntity c : modelo.classes()) {
            if (!Boolean.TRUE.equals(c.isAbstract())) {
                dtoDeEntidad.put(nombresJava.get(c.id()), nombresJava.get(c.id()) + "ResponseDTO");
            }
        }

        List<Map<String, Object>> resultado = new ArrayList<>();
        for (ClassEntity clase : modelo.classes()) {
            Map<String, Object> entidad =
                    entidad(clase, modelo, porId, nombresJava, paquete, tipos, dtoDeEntidad);
            entidad.put("t", tipos);
            entidad.put("importes", importesUsados(entidad, importables));
            resultado.add(entidad);
        }
        resolverTipoDeIdHeredado(resultado, tipos);
        resolverCamposHeredados(resultado);
        resolverReferencias(resultado);
        return resultado;
    }

    /**
     * Los campos que el DTO tiene que llevar: <b>los propios MAS los heredados</b>.
     *
     * <h2>Por que el DTO no lleva lo mismo que la entidad</h2>
     *
     * <p>La entidad declara solo sus campos, y eso esta bien: en JPA el padre declara los suyos y
     * la hija los hereda. Pero el DTO de entrada no describe una clase — describe <b>lo que el
     * cliente esta obligado a enviar</b>, y para crear un {@code Dueno} hay que mandar tambien
     * sus nombres y apellidos, que viven en {@code Persona}.
     *
     * <p>Sin esto, {@code DuenoRequestDTO} sale con {@code direccion}, {@code email} y
     * {@code fechaAlta}, y sin {@code nombres}. Como {@code Persona.nombres} es
     * {@code nullable = false}, <b>el API generado no puede crear un Dueno</b>: no hay por donde
     * mandar el dato y el insert viola el NOT NULL.
     *
     * <p>Y lo peor es que compila. El arnes lo daba por bueno porque compilar y mapear bien no
     * dicen nada sobre las restricciones de la base: el fallo aparece recien contra un PostgreSQL
     * real, en el primer POST. Lo encontro la confrontacion del catalogo de intenciones de la app
     * movil contra estos DTO — un test escrito para lo nuevo, que senialo lo viejo.
     *
     * <p>Los ancestros van PRIMERO y en orden de raiz a hoja, que es como se lee un formulario:
     * nombre y apellido antes que la direccion.
     */
    @SuppressWarnings("unchecked")
    private static void resolverCamposHeredados(List<Map<String, Object>> entidades) {
        Map<String, Map<String, Object>> porNombre = new HashMap<>();
        entidades.forEach(e -> porNombre.put((String) e.get("clase"), e));

        for (Map<String, Object> entidad : entidades) {
            List<Map<String, Object>> cadena = new ArrayList<>();
            Map<String, Object> actual = entidad;
            int guarda = 0;
            // La guarda corta una herencia circular. El validador la rechaza, pero este
            // generador puede recibir un diagrama que nadie valido, y colgarse seria peor.
            while (actual != null && guarda++ < 32) {
                cadena.add(0, actual);
                actual = porNombre.get((String) actual.get("extiende"));
            }

            List<Map<String, Object>> camposDto = new ArrayList<>();
            Set<String> vistos = new LinkedHashSet<>();
            for (Map<String, Object> nivel : cadena) {
                for (Map<String, Object> campo
                        : (List<Map<String, Object>>) nivel.get("campos")) {
                    // Si la hija redefine un campo del padre, gana el de la hija.
                    if (vistos.add((String) campo.get("campo"))) {
                        camposDto.add(campo);
                    }
                }
            }
            entidad.put("camposDto", camposDto);
        }
    }

    /**
     * Cada entidad necesita saber el tipo de su clave primaria para que su repositorio sea
     * {@code JpaRepository<Entidad, TipoDeId>}. Una subclase no declara {@code @Id} —lo hereda—,
     * así que hay que subir por la jerarquía hasta encontrar quién sí lo tiene.
     *
     * <p>Sin esto, el repositorio de {@code Dueno} saldría con el tipo equivocado y no compilaría:
     * es exactamente lo que la veterinaria, con su {@code Persona} abstracta, ejercita.
     */
    @SuppressWarnings("unchecked")
    private static void resolverTipoDeIdHeredado(
            List<Map<String, Object>> entidades, Map<String, String> tipos) {

        Map<String, Map<String, Object>> porNombre = new HashMap<>();
        entidades.forEach(e -> porNombre.put((String) e.get("clase"), e));

        for (Map<String, Object> entidad : entidades) {
            Map<String, Object> actual = entidad;
            Map<String, Object> id = (Map<String, Object>) actual.get("id");
            int guarda = 0;
            // La guarda evita un bucle infinito si el diagrama trae una herencia circular. El
            // validador del Día 10 la va a rechazar, pero el generador no puede colgarse.
            while (id == null && actual.get("extiende") != null && guarda++ < 32) {
                actual = porNombre.get((String) actual.get("extiende"));
                if (actual == null) {
                    break;
                }
                id = (Map<String, Object>) actual.get("id");
            }
            String tipoSimple = id == null ? "Long" : (String) id.get("tipo");
            String campoDelId = id == null ? "id" : (String) id.get("campo");
            entidad.put("tipoDelIdEfectivo", tipos.get(tipoSimple));
            // El getter de la clave puede vivir en un ancestro: Dueno hereda getId() de Persona.
            entidad.put("idGetter",
                    Character.toUpperCase(campoDelId.charAt(0)) + campoDelId.substring(1));
            entidad.put("importeDelIdEfectivo",
                    "UUID".equals(tipoSimple) && "UUID".equals(tipos.get("UUID"))
                            ? "java.util.UUID"
                            : null);
        }
    }

    private Map<String, Object> entidad(
            ClassEntity clase,
            CanonicalModel modelo,
            Map<String, ClassEntity> porId,
            Map<String, String> nombresJava,
            String paquete,
            Map<String, String> tipos,
            Map<String, String> dtoDeEntidad) {

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("paquete", paquete);
        e.put("nombreOriginal", clase.name());
        e.put("clase", nombresJava.get(clase.id()));
        e.put("tabla", literal(sanitizer.nombreDeTabla(clase.name())));
        // La regla vive en IdentifierSanitizer y la comparte la app movil, que le pega a estas
        // mismas rutas. Calculada por separado, un dia dejarian de coincidir y el sintoma
        // apareceria en el telefono de un usuario final como un 404 sin explicacion.
        e.put("rutaRest", sanitizer.rutaRest(clase.name()));
        e.put("esAbstracta", clase.isAbstract());

        // Herencia: el origen de un INHERITANCE es el hijo (§8.1 y los fixtures del Día 1).
        Optional<Relationship> herencia = modelo.relationships().stream()
                .filter(r -> r.type() == RelationshipType.INHERITANCE
                        && r.sourceClassId().equals(clase.id()))
                .findFirst();
        boolean esHija = herencia.isPresent();
        e.put("extiende", herencia.map(r -> nombresJava.get(r.targetClassId())).orElse(null));
        e.put("esPadre", modelo.relationships().stream()
                .anyMatch(r -> r.type() == RelationshipType.INHERITANCE
                        && r.targetClassId().equals(clase.id())));

        Set<String> usados = new LinkedHashSet<>();

        // --- clave primaria ------------------------------------------------------------------
        Optional<Attribute> pk = clase.attributes().stream()
                .filter(Attribute::isPrimaryKey)
                .findFirst();

        if (esHija) {
            // Una subclase NO declara @Id: lo hereda. Declararlo de nuevo es un error de mapeo
            // que Hibernate rechaza al arrancar y no al compilar, así que se escaparía del
            // criterio "100% compila" para aparecer recién en ejecución.
            e.put("id", null);
        } else if (pk.isPresent()) {
            e.put("id", campoDeId(pk.get(), usados));
        } else {
            // §11.2.2: inyección automática cuando el modelador no definió clave primaria.
            Map<String, Object> id = new LinkedHashMap<>();
            id.put("tipo", "Long");
            id.put("tipoCanonico", AttributeType.BIGINT.name());
            id.put("campo", "id");
            id.put("columna", "id");
            id.put("generacion", "IDENTITY");
            id.put("inyectado", true);
            e.put("id", id);
        }

        // --- atributos -----------------------------------------------------------------------
        List<Map<String, Object>> campos = new ArrayList<>();
        for (Attribute atributo : clase.attributes()) {
            if (atributo.isPrimaryKey()) {
                continue; // ya salió como @Id, o se descarta por venir de una subclase
            }
            campos.add(campo(atributo, usados));
        }
        e.put("campos", campos);
        e.put("metodos", metodos(clase, nombresDelDiagrama(nombresJava), dtoDeEntidad, e));

        // --- relaciones ----------------------------------------------------------------------
        List<Map<String, Object>> relaciones = new ArrayList<>();
        for (Relationship r : modelo.relationships()) {
            asociacion(r, clase, porId, nombresJava, tipos).ifPresent(relaciones::add);
        }
        e.put("relaciones", relaciones);
        e.put("tiposUsados", usados);
        return e;
    }

    /**
     * Deja sólo los imports que esta entidad realmente usa. Un archivo generado con veinte
     * imports muertos es ruido para quien después lo lee.
     */
    @SuppressWarnings("unchecked")
    private static List<String> importesUsados(
            Map<String, Object> entidad, Set<String> importables) {

        Set<String> usados = new LinkedHashSet<>((Set<String>) entidad.get("tiposUsados"));
        usados.add("jakarta.persistence.Entity");
        usados.add("jakarta.persistence.Table");
        if (!((List<?>) entidad.get("campos")).isEmpty()) {
            usados.add("jakarta.persistence.Column");
        }

        Map<String, Object> id = (Map<String, Object>) entidad.get("id");
        if (id != null) {
            usados.add("jakarta.persistence.Id");
            usados.add("jakarta.persistence.Column");
            if (id.get("generacion") != null) {
                usados.add("jakarta.persistence.GeneratedValue");
                usados.add("jakarta.persistence.GenerationType");
            }
        }
        if (Boolean.TRUE.equals(entidad.get("esPadre"))) {
            usados.add("jakarta.persistence.Inheritance");
            usados.add("jakarta.persistence.InheritanceType");
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) entidad.get("relaciones")) {
            usados.add("jakarta.persistence." + r.get("anotacion"));
            if (Boolean.TRUE.equals(r.get("esColeccion"))) {
                usados.add("java.util.List");
                usados.add("java.util.ArrayList");
            }
            if (r.get("joinColumn") != null || r.get("joinTable") != null) {
                usados.add("jakarta.persistence.JoinColumn");
            }
            if (r.get("joinTable") != null) {
                usados.add("jakarta.persistence.JoinTable");
            }
            if (Boolean.TRUE.equals(r.get("cascada"))) {
                usados.add("jakarta.persistence.CascadeType");
            }
        }
        // Se intersecta con los importables para descartar los que quedaron calificados por
        // colisión: esos no se importan, se escriben con el nombre completo.
        usados.retainAll(importables);
        List<String> ordenados = new ArrayList<>(usados);
        ordenados.sort(String::compareTo);
        return ordenados;
    }

    private static Set<String> nombresDelDiagrama(Map<String, String> nombresJava) {
        return new LinkedHashSet<>(nombresJava.values());
    }

    /**
     * Métodos del UML como firmas para la interface del servicio y stubs en la implementación
     * (§11.2.5).
     *
     * <p>Los tipos del diagrama son <b>texto libre</b>: nada impide que alguien escriba
     * {@code Fecha} o {@code lista de cosas} como tipo de retorno, y volcarlo tal cual al código
     * rompería la garantía de que compila. Por eso se traduce lo reconocible y todo lo demás cae
     * a {@code Object}: una firma menos precisa es infinitamente mejor que un archivo que no
     * compila, y el stub lleva un TODO que le dice al desarrollador qué decía el diagrama.
     */
    private List<Map<String, Object>> metodos(
            ClassEntity clase,
            Set<String> entidades,
            Map<String, String> dtoDeEntidad,
            Map<String, Object> destino) {
        Set<String> dtosUsados = new LinkedHashSet<>();
        // Los tipos escalares que aparecen SOLO en las firmas de los metodos tambien necesitan
        // su import: Barbero no tiene ningun atributo DATETIME pero su metodo estaDisponible
        // recibe un LocalDateTime, y sin esto el archivo no compila.
        Set<String> escalaresUsados = new LinkedHashSet<>();
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (Method metodo : clase.methods()) {
            List<Map<String, Object>> parametros = new ArrayList<>();
            List<String> firmaDeParametros = new ArrayList<>();
            int i = 0;
            for (Parameter parametro : metodo.parameters()) {
                String tipo = tipoJava(parametro.type(), entidades, dtoDeEntidad, dtosUsados, escalaresUsados);
                String nombre = sanitizer.nombreDeCampo(
                        parametro.name() == null || parametro.name().isBlank()
                                ? "arg" + i
                                : parametro.name());
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("tipo", tipo);
                p.put("nombre", nombre);
                parametros.add(p);
                firmaDeParametros.add(tipo + " " + nombre);
                i++;
            }
            String retorno = tipoJava(metodo.returnType(), entidades, dtoDeEntidad, dtosUsados, escalaresUsados);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", sanitizer.nombreDeCampo(metodo.name()));
            m.put("nombreOriginal", metodo.name());
            m.put("retorno", retorno);
            m.put("esVoid", "void".equals(retorno));
            m.put("parametros", parametros);
            m.put("firmaDeParametros", String.join(", ", firmaDeParametros));
            m.put("nombresDeParametros", parametros.stream()
                    .map(p -> String.valueOf(p.get("nombre")))
                    .toList());
            m.put("valorPorDefecto", valorPorDefecto(retorno));
            resultado.add(m);
        }
        destino.put("dtosUsadosEnMetodos", new ArrayList<>(dtosUsados));
        destino.put("escalaresUsadosEnMetodos", new ArrayList<>(escalaresUsados));
        return resultado;
    }

    /** Tipos que se pueden volcar al código generado sin riesgo. */
    private static final Set<String> TIPOS_SEGUROS = Set.of(
            "void", "String", "Integer", "Long", "Double", "Float", "Boolean", "BigDecimal",
            "LocalDate", "LocalDateTime", "UUID", "int", "long", "double", "float", "boolean");

    private static String tipoJava(
            String delDiagrama,
            Set<String> entidades,
            Map<String, String> dtoDeEntidad,
            Set<String> dtosUsados,
            Set<String> escalaresUsados) {

        if (delDiagrama == null || delDiagrama.isBlank()) {
            return "void";
        }
        String limpio = delDiagrama.trim();
        if (limpio.startsWith("List<") && limpio.endsWith(">")) {
            String interno = uno(limpio.substring(5, limpio.length() - 1).trim(),
                    entidades, dtoDeEntidad, dtosUsados, escalaresUsados);
            return "Object".equals(interno) ? "Object" : "List<" + interno + ">";
        }
        return uno(limpio, entidades, dtoDeEntidad, dtosUsados, escalaresUsados);
    }

    /**
     * Un tipo suelto del diagrama traducido a algo que compile.
     *
     * <p>Si nombra una entidad se devuelve <b>su DTO</b>, no la entidad: un servicio que expone
     * entidades JPA rompe el corte de capas que las capas 3-5 existen para sostener, y ademas
     * arrastra el grafo entero al serializar. Una entidad abstracta no tiene DTO, asi que cae a
     * {@code Object} como cualquier tipo desconocido.
     */
    private static String uno(
            String tipo,
            Set<String> entidades,
            Map<String, String> dtoDeEntidad,
            Set<String> dtosUsados,
            Set<String> escalaresUsados) {

        if (TIPOS_SEGUROS.contains(tipo)) {
            escalaresUsados.add(tipo);
            return tipo;
        }
        String dto = dtoDeEntidad.get(tipo);
        if (dto != null) {
            dtosUsados.add(dto);
            return dto;
        }
        return "Object";
    }

    /** Qué devuelve el stub para que el método compile mientras nadie lo implementa. */
    private static String valorPorDefecto(String retorno) {
        return switch (retorno) {
            case "void" -> null;
            case "int", "long", "double", "float" -> "0";
            case "boolean" -> "false";
            default -> retorno.startsWith("List<") ? "java.util.List.of()" : "null";
        };
    }

    /**
     * Las relaciones "a uno" que esta entidad posee, con el tipo de clave de la otra punta.
     *
     * <p>Son las que el DTO de entrada expone como {@code clienteId}: sin ellas no se puede crear
     * una Cita asociada a un Cliente, y el CRUD generado quedaria en entidades sueltas sin
     * relacion, que no es lo que dibuja nadie. La resolucion del id a la entidad la hace el
     * servicio —que ya tiene los repositorios— y no el mapper, que se queda tonto.
     *
     * <p>Va en una segunda pasada porque necesita el tipo de clave de OTRA entidad, que recien se
     * conoce cuando todas estan construidas.
     */
    @SuppressWarnings("unchecked")
    private static void resolverReferencias(List<Map<String, Object>> entidades) {
        Map<String, Map<String, Object>> porNombre = new HashMap<>();
        entidades.forEach(e -> porNombre.put((String) e.get("clase"), e));

        for (Map<String, Object> entidad : entidades) {
            List<Map<String, Object>> referencias = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) entidad.get("relaciones")) {
                boolean aUno = !Boolean.TRUE.equals(r.get("esColeccion"));
                if (!aUno || !Boolean.TRUE.equals(r.get("soyDuenio"))) {
                    continue;
                }
                Map<String, Object> otra = porNombre.get((String) r.get("clase"));
                if (otra == null) {
                    continue;
                }
                String campo = (String) r.get("campo");
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("campo", campo);
                ref.put("campoId", campo + "Id");
                ref.put("setter", campo.isEmpty()
                        ? campo
                        : Character.toUpperCase(campo.charAt(0)) + campo.substring(1));
                ref.put("clase", r.get("clase"));
                ref.put("tipoDelId", otra.get("tipoDelIdEfectivo"));
                ref.put("obligatorio", Boolean.TRUE.equals(r.get("obligatorio")));
                ref.put("repo", minuscularInicial((String) r.get("clase")) + "Repository");
                referencias.add(ref);
            }
            entidad.put("referencias", referencias);
        }
        // Cada entidad necesita saber como se llama el getter de la clave de las OTRAS, para
        // poder devolver sus identificadores en el DTO de salida.
        Map<String, String> gettersDeId = new LinkedHashMap<>();
        entidades.forEach(e -> gettersDeId.put(
                (String) e.get("clase"), (String) e.get("idGetter")));
        entidades.forEach(e -> e.put("idGetterDe", gettersDeId));
        entidades.forEach(ModeloDeGeneracion::importesDeLasCapasAltas);
    }

    /**
     * Los imports de los DTO, servicios y controladores.
     *
     * <p>Se calculan en la segunda pasada y no junto con la entidad porque tienen que incluir el
     * tipo de la clave <b>efectiva</b>, que en una subclase vive en un ancestro: sin esto, el
     * {@code DuenoResponseDTO} declara un {@code UUID} que nunca importa y no compila.
     *
     * <p>Es un unico conjunto ordenado para las dos capas, y eso tambien es a proposito: con dos
     * listas que se emiten uno detras de otro salen imports duplicados, que tampoco compilan.
     */
    @SuppressWarnings("unchecked")
    private static void importesDeLasCapasAltas(Map<String, Object> entidad) {
        Set<String> deDatos = new java.util.TreeSet<>();
        for (String usado : (Set<String>) entidad.get("tiposUsados")) {
            if (!usado.startsWith("jakarta.persistence") && !usado.startsWith("java.lang")) {
                deDatos.add(usado);
            }
        }
        String importeDelId = (String) entidad.get("importeDelIdEfectivo");
        if (importeDelId != null) {
            deDatos.add(importeDelId);
        }
        // Los tipos que solo aparecen en las firmas de los metodos del UML.
        for (String escalar : (List<String>) entidad.get("escalaresUsadosEnMetodos")) {
            agregarSiSeImporta(escalar, deDatos);
        }
        // Y el tipo de clave de las entidades REFERENCIADAS, que el DTO expone como groupId:
        // Order tiene clave Long y ningun atributo UUID, pero apunta a un Group de clave UUID.
        for (Map<String, Object> ref : (List<Map<String, Object>>) entidad.get("referencias")) {
            agregarSiSeImporta((String) ref.get("tipoDelId"), deDatos);
        }
        // Y los tipos de los campos HEREDADOS, que la entidad no declara y el DTO si: sin esto,
        // un padre con una fecha deja a la hija declarando un LocalDate que nunca importa.
        for (Map<String, Object> campo : (List<Map<String, Object>>) entidad.get("camposDto")) {
            agregarSiSeImporta((String) campo.get("tipo"), deDatos);
        }
        entidad.put("importesDto", new ArrayList<>(deDatos));

        // El servicio suma los DTO que aparecen en las firmas de los metodos del UML.
        Set<String> deServicio = new java.util.TreeSet<>(deDatos);
        String paquete = (String) entidad.get("paquete");
        for (String dto : (List<String>) entidad.get("dtosUsadosEnMetodos")) {
            // El propio DTO de la entidad ya se importa aparte; importarlo dos veces no compila.
            if (!dto.equals(entidad.get("clase") + "ResponseDTO")) {
                deServicio.add(paquete + ".service.dto." + dto);
            }
        }
        entidad.put("importesServicio", new ArrayList<>(deServicio));
    }

    private static String minuscularInicial(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Suma el import de un tipo simple, si es que necesita uno. */
    private static void agregarSiSeImporta(String tipo, Set<String> destino) {
        if (tipo == null || tipo.contains(".")) {
            return; // ya viene calificado por colision: no se importa, se escribe entero
        }
        String calificado = REGISTRO_DE_TIPOS.get(tipo);
        if (calificado != null && !calificado.startsWith("java.lang")) {
            destino.add(calificado);
        }
    }

    private Map<String, Object> campoDeId(Attribute pk, Set<String> usados) {
        String tipo = TIPOS.get(pk.type());
        agregarImporte(tipo, usados);
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("tipo", tipo);
        // El tipo tal como lo dibujo el modelador. El Java ya perdio la diferencia entre VARCHAR
        // y TEXT —los dos son String— y la vista previa del esquema necesita distinguirlos.
        id.put("tipoCanonico", pk.type().name());
        id.put("campo", sanitizer.nombreDeCampo(pk.name()));
        id.put("columna", literal(sanitizer.nombreDeColumna(pk.name())));
        // La estrategia sigue al tipo: una PK UUID con IDENTITY no arranca, y una Long con
        // GenerationType.UUID tampoco. Son errores que sólo aparecen en ejecución.
        id.put("generacion", switch (pk.type()) {
            case UUID -> "UUID";
            case INTEGER, BIGINT -> "IDENTITY";
            default -> null; // asignada por la aplicación, por ejemplo un código de negocio
        });
        id.put("inyectado", false);
        return id;
    }

    private Map<String, Object> campo(Attribute atributo, Set<String> usados) {
        String tipo = TIPOS.get(atributo.type());
        agregarImporte(tipo, usados);

        String campo = sanitizer.nombreDeCampo(atributo.name());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tipo", tipo);
        c.put("tipoCanonico", atributo.type().name());
        c.put("campo", campo);
        c.put("nombreOriginal", atributo.name());
        c.put("getter", sanitizer.enMayusculaInicial(campo));
        c.put("columna", literal(sanitizer.nombreDeColumna(atributo.name())));
        c.put("nullable", atributo.isNullable());
        c.put("unique", atributo.isUnique());
        // length sólo tiene sentido en textos acotados; precision/scale sólo en decimales.
        c.put("length", atributo.type() == AttributeType.VARCHAR ? atributo.length() : null);
        c.put("precision", atributo.type() == AttributeType.DECIMAL ? atributo.precision() : null);
        c.put("scale", atributo.type() == AttributeType.DECIMAL ? atributo.scale() : null);
        return c;
    }

    /**
     * Traduce una relación a la vista que le toca a <b>esta</b> clase, o vacío si no la involucra.
     *
     * <p>Acá se aplica la regla de {@code owningSide} congelada el Día 1: el lado dueño es el que
     * lleva la clave foránea, o sea el {@code @ManyToOne} con {@code @JoinColumn}; el otro lleva
     * el {@code mappedBy}. Que los dos lados salgan de un único cálculo es lo que garantiza que
     * el {@code mappedBy} apunte a un campo que existe de verdad: escritos por separado se
     * desincronizan, y eso Hibernate lo rechaza al arrancar, no al compilar.
     */
    private Optional<Map<String, Object>> asociacion(
            Relationship r,
            ClassEntity clase,
            Map<String, ClassEntity> porId,
            Map<String, String> nombresJava,
            Map<String, String> tipos) {

        // La herencia se resuelve con 'extends'; la dependencia no genera campo, porque es una
        // relación de uso y no de persistencia.
        if (r.type() == RelationshipType.INHERITANCE || r.type() == RelationshipType.DEPENDENCY) {
            return Optional.empty();
        }
        boolean soyOrigen = r.sourceClassId().equals(clase.id());
        boolean soyDestino = r.targetClassId().equals(clase.id());
        if (!soyOrigen && !soyDestino) {
            return Optional.empty();
        }
        // Una relación cuyo otro extremo no existe es el residuo de un DELETE_CLASS mal aplicado.
        // El validador del Día 10 la rechaza; el generador, mientras tanto, no debe explotar.
        if (!porId.containsKey(r.sourceClassId()) || !porId.containsKey(r.targetClassId())) {
            return Optional.empty();
        }

        // D-08: NO se lee r.owningSide() directo. Cuando las multiplicidades deciden donde va
        // la clave foranea, mandan ellas — el campo del diagrama puede contradecirlas, y de
        // hecho lo hace cada vez que nadie lo toco, porque el default de §7 es SOURCE.
        //
        // Creerle al campo producia @ManyToOne(mappedBy = "..."), que no existe en JPA: el ZIP
        // no compilaba. La regla vive en OwningSideRule y la comparte el validador, para que el
        // aviso que le damos al modelador y lo que hace el generador no puedan separarse.
        boolean duenioEsOrigen = OwningSideRule.efectivo(r) == OwningSide.SOURCE;
        boolean soyDuenio = soyOrigen == duenioEsOrigen;

        String idDelOtro = soyOrigen ? r.targetClassId() : r.sourceClassId();
        String claseDelOtro = nombresJava.get(idDelOtro);

        // El rol del campo que vive en MI clase es el que describe al OTRO extremo.
        String rol = soyOrigen ? r.targetRole() : r.sourceRole();
        String rolDelOtro = soyOrigen ? r.sourceRole() : r.targetRole();

        Multiplicity miExtremo = soyOrigen ? r.sourceMultiplicity() : r.targetMultiplicity();
        Multiplicity extremoDelOtro = soyOrigen ? r.targetMultiplicity() : r.sourceMultiplicity();

        boolean m2m = r.type() == RelationshipType.MANY_TO_MANY;
        // Mi campo es una colección si el OTRO extremo admite varios.
        boolean esColeccion = m2m || esColeccion(extremoDelOtro);
        boolean elOtroMeVeComoColeccion = m2m || esColeccion(miExtremo);

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("clase", claseDelOtro);
        a.put("campo", nombreDeCampoDeRelacion(rol, claseDelOtro, esColeccion));
        a.put("esColeccion", esColeccion);
        a.put("soyDuenio", soyDuenio);

        // La composición implica que las partes no viven sin el todo: el lado del "todo" —el del
        // rombo en UML, que es el origen— propaga el borrado. Sin esto, borrar una Cita deja sus
        // Pagos huérfanos en la base.
        a.put("cascada", r.type() == RelationshipType.COMPOSITION && soyOrigen);

        if (m2m) {
            a.put("anotacion", "ManyToMany");
        } else if (esColeccion) {
            a.put("anotacion", "OneToMany");
        } else if (elOtroMeVeComoColeccion) {
            a.put("anotacion", "ManyToOne");
        } else {
            a.put("anotacion", "OneToOne");
        }

        if (soyDuenio) {
            a.put("mappedBy", null);
            if (m2m) {
                a.put("joinTable", literal(r.joinTableName() != null
                        ? r.joinTableName()
                        : sanitizer.nombreDeTablaIntermedia(
                                porId.get(r.sourceClassId()).name(),
                                porId.get(r.targetClassId()).name())));
                a.put("joinColumn", columnaDeFk(porId.get(clase.id()).name()));
                a.put("inverseJoinColumn", columnaDeFk(porId.get(idDelOtro).name()));
            } else {
                a.put("joinTable", null);
                a.put("joinColumn", columnaDeFk(porId.get(idDelOtro).name()));
                a.put("obligatorio", extremoDelOtro == Multiplicity.ONE_ONE);
            }
        } else {
            // mappedBy apunta al campo que la otra clase creó para mí, calculado con la misma
            // función: es lo que evita que los dos lados se desincronicen.
            a.put("mappedBy", nombreDeCampoDeRelacion(
                    rolDelOtro, nombresJava.get(clase.id()), elOtroMeVeComoColeccion));
            a.put("joinTable", null);
            a.put("joinColumn", null);
        }

        // Los atributos de la anotación se arman acá y no en la plantilla, por la misma razón
        // que todo lo demás: para poder decidir sin lógica en el .ftl, y para que una anotación
        // sin atributos salga como @ManyToOne y no como @ManyToOne().
        List<String> atributos = new ArrayList<>();
        if (a.get("mappedBy") != null) {
            atributos.add("mappedBy = \"" + a.get("mappedBy") + "\"");
        }
        if (Boolean.TRUE.equals(a.get("cascada"))) {
            atributos.add("cascade = " + tipos.get("CascadeType") + ".ALL");
            atributos.add("orphanRemoval = true");
        }
        a.put("atributos", atributos.isEmpty() ? null : String.join(", ", atributos));
        return Optional.of(a);
    }

    private String columnaDeFk(String nombreDeClase) {
        // La columna se arma sobre el nombre ya en snake_case y se vuelve a pasar por el
        // saneador, porque "order_id" no es reservada aunque "order" sí lo sea.
        return literal(sanitizer.nombreDeColumna(nombreDeClase + "Id"));
    }

    private static boolean esColeccion(Multiplicity m) {
        return m != null && m.isCollection();
    }

    /**
     * Nombre del campo de una relación. Se usa el rol del diagrama si existe —es lo que el
     * modelador quiso decir— y si no se deriva del nombre de la clase, en plural si es colección.
     */
    /**
     * Delega en {@link IdentifierSanitizer}, donde vive la regla. La comparte la app movil, que
     * tiene que rellenar exactamente estos campos: calculada por separado, un dia dejaria de
     * coincidir y el sintoma seria una orden dictada que el servidor rechaza siempre.
     */
    private String nombreDeCampoDeRelacion(String rol, String claseDelOtro, boolean esColeccion) {
        return sanitizer.nombreDeCampoDeRelacion(rol, claseDelOtro, esColeccion);
    }

    private static void agregarImporte(String tipo, Set<String> usados) {
        String calificado = REGISTRO_DE_TIPOS.get(tipo);
        if (calificado != null) {
            usados.add(calificado);
        }
    }

    /** Escapa un identificador para que pueda ir dentro de un literal Java. */
    private static String literal(String valor) {
        return valor == null ? null : valor.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
