package com.modelcollab.generator.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
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

        List<Map<String, Object>> resultado = new ArrayList<>();
        for (ClassEntity clase : modelo.classes()) {
            Map<String, Object> entidad =
                    entidad(clase, modelo, porId, nombresJava, paquete, tipos);
            entidad.put("t", tipos);
            entidad.put("importes", importesUsados(entidad, importables));
            resultado.add(entidad);
        }
        resolverTipoDeIdHeredado(resultado, tipos);
        return resultado;
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
            entidad.put("tipoDelIdEfectivo", tipos.get(tipoSimple));
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
            Map<String, String> tipos) {

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("paquete", paquete);
        e.put("nombreOriginal", clase.name());
        e.put("clase", nombresJava.get(clase.id()));
        e.put("tabla", literal(sanitizer.nombreDeTabla(clase.name())));
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

    private Map<String, Object> campoDeId(Attribute pk, Set<String> usados) {
        String tipo = TIPOS.get(pk.type());
        agregarImporte(tipo, usados);
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("tipo", tipo);
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

        boolean duenioEsOrigen = r.owningSide() == OwningSide.SOURCE;
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
    private String nombreDeCampoDeRelacion(String rol, String claseDelOtro, boolean esColeccion) {
        if (rol != null && !rol.isBlank()) {
            return sanitizer.nombreDeCampo(rol);
        }
        String base = sanitizer.nombreDeCampo(claseDelOtro);
        return esColeccion ? base + "s" : base;
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
