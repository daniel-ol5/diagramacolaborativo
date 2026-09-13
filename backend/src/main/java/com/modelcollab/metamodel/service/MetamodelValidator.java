package com.modelcollab.metamodel.service;

import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.dto.ValidationReportDTO.ValidationIssue;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Catálogo de invariantes del generador, "100% Compila" (§11.2). Función pura sobre el modelo
 * canónico: no toca la base ni conoce HTTP.
 *
 * <h2>Qué bloquea y qué sólo avisa</h2>
 *
 * <p>Bloquea lo que <b>no puede existir en Java o en el esquema</b>: una clase con dos padres, una
 * relación que apunta a una clase que ya no está, dos clases que colisionan en el mismo nombre
 * Java. Con cualquiera de esas, generar produciría un ZIP que no compila — y entregar eso es peor
 * que no entregar nada.
 *
 * <p>Avisa de lo que el generador <b>resuelve solo</b>, para que el modelador sepa qué decidió por
 * él: la clave primaria que se le inyectó a una clase que no declaraba ninguna, el lado de la FK
 * que se eligió en una relación simétrica, el atributo que hubo que renombrar por ser palabra
 * reservada. Ninguna de esas impide generar.
 */
@Component
public class MetamodelValidator {

    // --- errores bloqueantes --------------------------------------------------------------
    public static final String HERENCIA_MULTIPLE = "MULTIPLE_INHERITANCE";
    public static final String RELACION_HUERFANA = "ORPHAN_RELATIONSHIP";
    public static final String HERENCIA_CIRCULAR = "CIRCULAR_INHERITANCE";
    public static final String NOMBRE_DUPLICADO = "DUPLICATE_CLASS_NAME";
    public static final String CLASE_SIN_NOMBRE = "MISSING_CLASS_NAME";
    public static final String ATRIBUTO_DUPLICADO = "DUPLICATE_ATTRIBUTE";
    public static final String DIAGRAMA_VACIO = "EMPTY_DIAGRAM";

    // --- advertencias ---------------------------------------------------------------------
    public static final String CLAVE_INYECTADA = "PRIMARY_KEY_INJECTED";
    public static final String CLAVE_MULTIPLE = "MULTIPLE_PRIMARY_KEYS";
    public static final String OWNING_SIDE_NO_DERIVABLE = "OWNING_SIDE_NOT_DERIVABLE";
    public static final String OWNING_SIDE_CONTRADICE = "OWNING_SIDE_CONTRADICTS";
    public static final String RESERVADA_JAVA = "JAVA_RESERVED_WORD";
    public static final String RESERVADA_SQL = "SQL_RESERVED_WORD";
    public static final String CLASE_SIN_ATRIBUTOS = "CLASS_WITHOUT_ATTRIBUTES";
    public static final String TIPO_DE_METODO_DESCONOCIDO = "UNKNOWN_METHOD_TYPE";

    private final IdentifierSanitizer sanitizer;

    public MetamodelValidator(IdentifierSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * Valida el diagrama entero.
     *
     * <p>Sin ningun campo mutable: este componente es un singleton de Spring y dos validaciones
     * simultaneas comparten la instancia. Todo lo que hace falta durante el recorrido viaja como
     * parametro, aunque cueste un argumento mas en unas cuantas firmas.
     */
    public ValidationReportDTO validar(CanonicalModel modelo) {
        List<ValidationIssue> errores = new ArrayList<>();
        List<ValidationIssue> advertencias = new ArrayList<>();

        if (modelo.classes().isEmpty()) {
            errores.add(new ValidationIssue(DIAGRAMA_VACIO, null, null,
                    "El diagrama no tiene ninguna clase: no hay nada que generar."));
            return ValidationReportDTO.de(errores, advertencias);
        }

        Map<String, ClassEntity> porId = new HashMap<>();
        modelo.classes().forEach(c -> porId.put(c.id(), c));

        Set<String> nombresDelDiagrama = new HashSet<>();
        modelo.classes().forEach(c -> {
            if (c.name() != null) {
                nombresDelDiagrama.add(c.name());
            }
        });

        nombresDeClase(modelo, errores, advertencias);
        clases(modelo, nombresDelDiagrama, errores, advertencias);
        herencia(modelo, porId, errores);
        relaciones(modelo, porId, errores, advertencias);

        return ValidationReportDTO.de(errores, advertencias);
    }

    // ------------------------------------------------------------------ clases

    private void nombresDeClase(
            CanonicalModel modelo, List<ValidationIssue> errores,
            List<ValidationIssue> advertencias) {

        Map<String, String> vistos = new HashMap<>();
        for (ClassEntity clase : modelo.classes()) {
            if (clase.name() == null || clase.name().isBlank()) {
                errores.add(new ValidationIssue(CLASE_SIN_NOMBRE, clase.id(), null,
                        "Hay una clase sin nombre: el generador no puede nombrar su archivo."));
                continue;
            }
            // Dos clases distintas pueden llamarse "Detalle de Orden" y "detalle-de-orden" y
            // saneadas terminan siendo la misma clase Java, que se pisaría a sí misma. Se compara
            // el nombre ya saneado, no el del diagrama.
            String java = sanitizer.nombreDeClase(clase.name());
            String anterior = vistos.put(java, clase.name());
            if (anterior != null) {
                errores.add(new ValidationIssue(NOMBRE_DUPLICADO, clase.id(), clase.name(),
                        "Las clases '" + anterior + "' y '" + clase.name()
                                + "' generan la misma clase Java " + java + "."));
            }
            if (sanitizer.esReservadaDeSql(java)) {
                advertencias.add(new ValidationIssue(RESERVADA_SQL, clase.id(), clase.name(),
                        "'" + clase.name() + "' es palabra reservada de SQL: su tabla se va a "
                                + "escapar como " + sanitizer.nombreDeTabla(clase.name()) + "."));
            }
        }
    }

    private void clases(
            CanonicalModel modelo, Set<String> nombresDelDiagrama,
            List<ValidationIssue> errores, List<ValidationIssue> advertencias) {

        for (ClassEntity clase : modelo.classes()) {
            List<Attribute> claves = clase.attributes().stream()
                    .filter(Attribute::isPrimaryKey)
                    .toList();

            if (claves.isEmpty() && !esHija(modelo, clase)) {
                // §11.2.2: no es un error, el generador inyecta la clave. Pero el modelador tiene
                // que enterarse de que la tabla va a tener una columna que él no dibujó.
                advertencias.add(new ValidationIssue(CLAVE_INYECTADA, clase.id(), clase.name(),
                        "'" + clase.name() + "' no declara clave primaria: se le va a inyectar "
                                + "un campo id de tipo Long autogenerado."));
            }
            if (claves.size() > 1) {
                advertencias.add(new ValidationIssue(CLAVE_MULTIPLE, clase.id(), clase.name(),
                        "'" + clase.name() + "' declara " + claves.size() + " claves primarias: "
                                + "se va a usar '" + claves.get(0).name()
                                + "' y las demás quedan como columnas normales."));
            }
            if (clase.attributes().isEmpty() && clase.methods().isEmpty()) {
                advertencias.add(new ValidationIssue(CLASE_SIN_ATRIBUTOS, clase.id(), clase.name(),
                        "'" + clase.name() + "' no tiene atributos ni métodos: se va a generar "
                                + "una entidad con sólo su clave primaria."));
            }
            atributos(clase, errores, advertencias);
            metodos(clase, nombresDelDiagrama, advertencias);
        }
    }

    private void atributos(
            ClassEntity clase, List<ValidationIssue> errores,
            List<ValidationIssue> advertencias) {

        Map<String, String> vistos = new HashMap<>();
        for (Attribute atributo : clase.attributes()) {
            if (atributo.name() == null || atributo.name().isBlank()) {
                errores.add(new ValidationIssue(ATRIBUTO_DUPLICADO, atributo.id(), null,
                        "'" + clase.name() + "' tiene un atributo sin nombre."));
                continue;
            }
            String campo = sanitizer.nombreDeCampo(atributo.name());
            String anterior = vistos.put(campo, atributo.name());
            if (anterior != null) {
                // Dos campos con el mismo nombre en la misma clase no compilan, punto.
                errores.add(new ValidationIssue(ATRIBUTO_DUPLICADO, atributo.id(), atributo.name(),
                        "'" + clase.name() + "' tiene dos atributos que generan el mismo campo "
                                + campo + ": '" + anterior + "' y '" + atributo.name() + "'."));
            }
            if (sanitizer.esReservadaDeJava(atributo.name())) {
                advertencias.add(new ValidationIssue(RESERVADA_JAVA, atributo.id(), atributo.name(),
                        "'" + atributo.name() + "' es palabra reservada de Java: el campo se va a "
                                + "llamar " + campo + " y la columna conserva el nombre original."));
            }
        }
    }

    private void metodos(
            ClassEntity clase, Set<String> nombresDelDiagrama,
            List<ValidationIssue> advertencias) {
        for (Method metodo : clase.methods()) {
            String retorno = metodo.returnType();
            if (retorno != null && !retorno.isBlank() && !esTipoReconocible(retorno, nombresDelDiagrama)) {
                advertencias.add(new ValidationIssue(
                        TIPO_DE_METODO_DESCONOCIDO, metodo.id(), metodo.name(),
                        "El método '" + metodo.name() + "' devuelve '" + retorno
                                + "', que el generador no reconoce: la firma va a usar Object."));
            }
        }
    }

    /** Los mismos tipos que sabe traducir el generador; el resto degrada a Object. */
    private static final Set<String> TIPOS_RECONOCIBLES = Set.of(
            "void", "String", "Integer", "Long", "Double", "Float", "Boolean", "BigDecimal",
            "LocalDate", "LocalDateTime", "UUID", "int", "long", "double", "float", "boolean");

    private static boolean esTipoReconocible(String tipo, Set<String> nombresDelDiagrama) {
        String limpio = tipo.trim();
        if (limpio.startsWith("List<") && limpio.endsWith(">")) {
            limpio = limpio.substring(5, limpio.length() - 1).trim();
        }
        // Un tipo que nombra una clase del diagrama también es reconocible: el generador lo
        // traduce a su DTO.
        return TIPOS_RECONOCIBLES.contains(limpio) || nombresDelDiagrama.contains(limpio);
    }

    // ---------------------------------------------------------------- herencia

    private boolean esHija(CanonicalModel modelo, ClassEntity clase) {
        return modelo.relationships().stream()
                .anyMatch(r -> r.type() == RelationshipType.INHERITANCE
                        && clase.id().equals(r.sourceClassId()));
    }

    /**
     * §11.2.3: <b>Java no soporta herencia múltiple de clases.</b> Es error bloqueante, no
     * advertencia: no hay forma de generar una clase con dos padres, y cualquier cosa que el
     * generador eligiera sería inventarse la mitad del modelo.
     */
    private void herencia(
            CanonicalModel modelo, Map<String, ClassEntity> porId, List<ValidationIssue> errores) {

        Map<String, List<String>> padres = new HashMap<>();
        for (Relationship r : modelo.relationships()) {
            if (r.type() == RelationshipType.INHERITANCE) {
                padres.computeIfAbsent(r.sourceClassId(), k -> new ArrayList<>())
                        .add(r.targetClassId());
            }
        }
        for (Map.Entry<String, List<String>> entrada : padres.entrySet()) {
            ClassEntity hija = porId.get(entrada.getKey());
            if (hija == null) {
                continue; // la relación huérfana se reporta aparte
            }
            if (entrada.getValue().size() > 1) {
                // Se listan TODOS los padres, no sólo el primero: el modelador necesita saber
                // cuáles son las dos herencias en conflicto para poder borrar una.
                String nombres = entrada.getValue().stream()
                        .map(id -> porId.containsKey(id) ? porId.get(id).name() : id)
                        .collect(java.util.stream.Collectors.joining("' y '"));
                errores.add(new ValidationIssue(HERENCIA_MULTIPLE, hija.id(), hija.name(),
                        "'" + hija.name() + "' hereda de '" + nombres
                                + "': Java no soporta herencia múltiple de clases."));
            }
        }
        circularidad(padres, porId, errores);
    }

    /**
     * Una jerarquía circular —A extiende B que extiende A— no compila y además colgaría a
     * cualquier recorrido de la jerarquía. El generador tiene una guarda para no colgarse, pero
     * eso es una red de seguridad: acá se rechaza de frente.
     */
    private void circularidad(
            Map<String, List<String>> padres, Map<String, ClassEntity> porId,
            List<ValidationIssue> errores) {

        for (String inicio : padres.keySet()) {
            Set<String> visitados = new HashSet<>();
            String actual = inicio;
            while (actual != null && visitados.add(actual)) {
                List<String> susPadres = padres.get(actual);
                actual = susPadres == null || susPadres.isEmpty() ? null : susPadres.get(0);
            }
            if (actual != null && actual.equals(inicio)) {
                ClassEntity clase = porId.get(inicio);
                errores.add(new ValidationIssue(HERENCIA_CIRCULAR, inicio,
                        clase == null ? null : clase.name(),
                        "La jerarquía de herencia de '"
                                + (clase == null ? inicio : clase.name()) + "' es circular."));
            }
        }
    }

    // -------------------------------------------------------------- relaciones

    private void relaciones(
            CanonicalModel modelo, Map<String, ClassEntity> porId,
            List<ValidationIssue> errores, List<ValidationIssue> advertencias) {

        for (Relationship r : modelo.relationships()) {
            ClassEntity origen = porId.get(r.sourceClassId());
            ClassEntity destino = porId.get(r.targetClassId());

            if (origen == null || destino == null) {
                // El residuo que deja un DELETE_CLASS mal aplicado. Generar con esto produce una
                // @ManyToOne que apunta a una clase inexistente: no compila.
                String cual = origen == null ? r.sourceClassId() : r.targetClassId();
                errores.add(new ValidationIssue(RELACION_HUERFANA, r.id(), null,
                        "Una relación apunta a la clase " + cual
                                + ", que no existe en el diagrama."));
                continue;
            }
            owningSide(r, origen, destino, advertencias);
        }
    }

    /**
     * La regla de {@code owningSide} congelada el Día 1. Sólo produce advertencias: el generador
     * sabe qué hacer en todos los casos, pero en dos de ellos conviene que el modelador se entere
     * de qué se decidió por él.
     */
    private void owningSide(
            Relationship r, ClassEntity origen, ClassEntity destino,
            List<ValidationIssue> advertencias) {

        if (r.type() == RelationshipType.INHERITANCE
                || r.type() == RelationshipType.DEPENDENCY) {
            return; // no llevan FK
        }
        if (r.type() == RelationshipType.MANY_TO_MANY) {
            return; // el campo es una elección genuina del modelador: dónde va la @JoinTable
        }

        Multiplicity sm = r.sourceMultiplicity();
        Multiplicity tm = r.targetMultiplicity();

        if (!OwningSideRule.esDerivable(r)) {
            // Caso 3: ambos extremos escalares e IGUALES. No hay respuesta mecánica, así que el
            // campo decide el esquema sin que nadie pueda verificarlo — y como el constructor
            // compacto ya normalizó el default, no se distingue "vino ausente" de "lo eligieron".
            // Por eso se avisa SIEMPRE, aunque el valor sea el correcto.
            advertencias.add(new ValidationIssue(OWNING_SIDE_NO_DERIVABLE, r.id(), null,
                    "La relación entre '" + origen.name() + "' y '" + destino.name()
                            + "' es simétrica (" + texto(sm) + " ↔ " + texto(tm)
                            + "): la clave foránea se va a poner del lado "
                            + r.owningSide() + " porque así lo declara el diagrama. Verificá que "
                            + "sea intencional."));
            return;
        }

        // La misma regla que aplica el generador, no una copia: si fueran dos, este aviso
        // podria volver a prometer algo que el generador no hace. Ya paso, y el sintoma fue un
        // ZIP que no compilaba.
        String derivado = OwningSideRule.efectivo(r).name();

        if (!derivado.equals(r.owningSide().name())) {
            advertencias.add(new ValidationIssue(OWNING_SIDE_CONTRADICE, r.id(), null,
                    "En la relación '" + origen.name() + "' " + texto(sm) + " → '"
                            + destino.name() + "' " + texto(tm) + " el diagrama declara owningSide="
                            + r.owningSide() + ", pero por las multiplicidades corresponde "
                            + derivado + ". El generador va a usar " + derivado + "."));
        }
    }

    private static boolean esColeccion(Multiplicity m) {
        return m != null && m.isCollection();
    }

    private static String texto(Multiplicity m) {
        return m == null ? "?" : m.getValue();
    }
}
