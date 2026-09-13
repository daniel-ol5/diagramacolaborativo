package com.modelcollab.collaboration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Package;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.Waypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Aplica una operación de §8.1 al modelo canónico. <b>Función pura:</b> no toca la base, no
 * conoce STOMP y no tiene estado — recibe un modelo y devuelve otro.
 *
 * <p>Que sea pura es deliberado y vale más que la elegancia: es la pieza donde vive la semántica
 * de las 22 operaciones, o sea donde más fácil se cuela un error, y así se puede probar entera
 * sin base de datos ni contexto de Spring (no hay Docker en el equipo, desvío D-01). El
 * {@code DiagramMutationService} se queda con la transacción y la persistencia, que es lo que sí
 * necesita infraestructura.
 *
 * <p>El modelo canónico son records inmutables, así que "modificar" es reconstruir. No es
 * ineficiencia: es lo que hace imposible que dos hilos que atienden a dos colaboradores corrompan
 * la misma clase, que es el requisito de §2.3.
 */
@Component
public class CanonicalMutationApplier {

    private final ObjectMapper objectMapper;

    public CanonicalMutationApplier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalModel apply(
            CanonicalModel modelo, OperationType op, String targetId, Map<String, Object> payload) {

        if (!op.implementada()) {
            throw new MutacionRechazadaException(
                    MutacionRechazadaException.OP_NO_IMPLEMENTADA,
                    501,
                    "La operación " + op + " todavía no está implementada en el servidor");
        }
        Map<String, Object> datos = payload == null ? Map.of() : payload;

        return switch (op) {
            case ADD_CLASS -> agregarClase(modelo, datos);
            case MOVE_CLASS -> mapearClase(modelo, targetId, c -> new ClassEntity(
                    c.id(), c.name(), c.visibility(), c.isAbstract(),
                    new Position(numero(datos, "x"), numero(datos, "y")),
                    c.width(), c.height(), c.attributes(), c.methods()));
            case RESIZE_CLASS -> mapearClase(modelo, targetId, c -> new ClassEntity(
                    c.id(), c.name(), c.visibility(), c.isAbstract(), c.position(),
                    numero(datos, "width"), numero(datos, "height"),
                    c.attributes(), c.methods()));
            case RENAME_CLASS -> mapearClase(modelo, targetId, c -> new ClassEntity(
                    c.id(), texto(datos, "name"), c.visibility(), c.isAbstract(), c.position(),
                    c.width(), c.height(), c.attributes(), c.methods()));
            case DELETE_CLASS -> borrarClase(modelo, targetId);

            case ADD_ATTRIBUTE -> mapearClase(modelo, targetId, c -> conAtributos(
                    c, agregar(c.attributes(), convertir(datos, Attribute.class), Attribute::id)));
            case UPDATE_ATTRIBUTE -> reemplazarAtributo(modelo, targetId, datos);
            case DELETE_ATTRIBUTE -> borrarAtributo(modelo, targetId);

            case ADD_METHOD -> mapearClase(modelo, targetId, c -> conMetodos(
                    c, agregar(c.methods(), convertir(datos, Method.class), Method::id)));
            case UPDATE_METHOD -> reemplazarMetodo(modelo, targetId, datos);
            case DELETE_METHOD -> borrarMetodo(modelo, targetId);

            case ADD_RELATIONSHIP -> conRelaciones(modelo, agregar(
                    modelo.relationships(), convertir(datos, Relationship.class),
                    Relationship::id));
            case UPDATE_WAYPOINTS -> mapearRelacion(modelo, targetId, r -> new Relationship(
                    r.id(), r.sourceClassId(), r.targetClassId(), r.type(), r.owningSide(),
                    r.joinTableName(), r.sourceMultiplicity(), r.targetMultiplicity(),
                    r.sourceRole(), r.targetRole(), r.isNavigable(), waypoints(datos)));
            case DELETE_RELATIONSHIP -> conRelaciones(
                    modelo, quitar(modelo.relationships(), targetId, Relationship::id,
                            "La relación"));

            case ADD_PACKAGE -> conPaquetes(modelo, agregar(
                    modelo.packages(), convertir(datos, Package.class), Package::id));
            case UPDATE_PACKAGE -> mapearPaquete(
                    modelo, targetId, p -> convertir(datos, Package.class));
            // Las clases del paquete NO se borran: quedan sin paquete (§8.1).
            case DELETE_PACKAGE -> conPaquetes(
                    modelo, quitar(modelo.packages(), targetId, Package::id, "El paquete"));

            case BULK_MERGE -> fusionar(modelo, datos);

            default -> throw new MutacionRechazadaException(
                    MutacionRechazadaException.OP_NO_IMPLEMENTADA, 501,
                    "La operación " + op + " no se aplica al estado canónico");
        };
    }

    /**
     * Nombre de cualquier elemento del modelo por su id, si existe.
     *
     * <p>Lo usan los servicios <b>antes</b> de aplicar un borrado, para guardar en la bitácora
     * qué se borró. Sin eso, la fila de un {@code DELETE_CLASS} queda con payload vacío y el
     * nombre del elemento se pierde para siempre: no está en el estado nuevo, porque se borró,
     * ni en el viejo, porque no se guarda. Es la diferencia entre un historial y una lista de
     * identificadores.
     */
    public static Optional<String> nombreDe(CanonicalModel modelo, String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ClassEntity c : modelo.classes()) {
            if (id.equals(c.id())) {
                return Optional.of(c.name());
            }
            for (Attribute a : c.attributes()) {
                if (id.equals(a.id())) {
                    return Optional.of(a.name());
                }
            }
            for (Method m : c.methods()) {
                if (id.equals(m.id())) {
                    return Optional.of(m.name());
                }
            }
        }
        return modelo.packages().stream()
                .filter(pk -> id.equals(pk.id()))
                .map(Package::name)
                .findFirst();
    }

    /**
     * Reemplaza el diagrama entero por el sub-grafo recibido (§8.1, {@code BULK_MERGE}).
     *
     * <p>Es un <b>reemplazo</b> y no una fusión elemento por elemento, y la diferencia importa:
     * importar un XMI trae un modelo completo con sus propios identificadores, y mezclarlo con lo
     * que ya había produciría un diagrama con dos jerarquías que nadie dibujó. El caso de uso es
     * "traé este diagrama", no "agregá estas clases a lo que tengo".
     *
     * <p>Se conserva la {@code mutationVersion} de quien llama: el número de secuencia lo asigna
     * el servicio, no el payload.
     */
    private CanonicalModel fusionar(CanonicalModel modelo, Map<String, Object> datos) {
        CanonicalModel entrante = convertir(datos, CanonicalModel.class);
        if (entrante.classes().isEmpty()) {
            throw MutacionRechazadaException.payloadInvalido(
                    "Un BULK_MERGE sin ninguna clase dejaría el diagrama vacío");
        }
        return new CanonicalModel(
                modelo.schemaVersion(),
                modelo.mutationVersion(),
                entrante.packages(),
                entrante.classes(),
                entrante.relationships());
    }

    // ------------------------------------------------------------------ clases

    private CanonicalModel agregarClase(CanonicalModel modelo, Map<String, Object> datos) {
        ClassEntity nueva = convertir(datos, ClassEntity.class);
        return conClases(modelo, agregar(modelo.classes(), nueva, ClassEntity::id));
    }

    /**
     * Borra la clase y <b>limpia las relaciones huérfanas en cascada</b> (§8.1). Sin esta
     * limpieza el diagrama queda con conectores que apuntan a un id inexistente — exactamente el
     * modelo de {@code invalid-cases/relacion-huerfana.json}, que el validador del Día 10 tiene
     * que rechazar. Un servidor que produce sus propios casos inválidos no es un servidor.
     */
    private CanonicalModel borrarClase(CanonicalModel modelo, String classId) {
        List<ClassEntity> clases = quitar(modelo.classes(), classId, ClassEntity::id, "La clase");

        List<Relationship> relaciones = modelo.relationships().stream()
                .filter(r -> !classId.equals(r.sourceClassId())
                        && !classId.equals(r.targetClassId()))
                .toList();

        // Y se saca de la lista de miembros de cualquier paquete que la contuviera.
        List<Package> paquetes = modelo.packages().stream()
                .map(p -> p.classIds().contains(classId)
                        ? new Package(p.id(), p.name(),
                                p.classIds().stream().filter(i -> !classId.equals(i)).toList())
                        : p)
                .toList();

        return new CanonicalModel(
                modelo.schemaVersion(), modelo.mutationVersion(), paquetes, clases, relaciones);
    }

    // -------------------------------------------------------------- atributos

    private CanonicalModel reemplazarAtributo(
            CanonicalModel modelo, String attrId, Map<String, Object> datos) {
        Attribute nuevo = convertir(datos, Attribute.class);
        exigirMismoId(attrId, nuevo.id(), "atributo");
        return mapearClaseQueContiene(modelo, attrId, Attribute::id, ClassEntity::attributes,
                c -> conAtributos(c, reemplazar(c.attributes(), attrId, Attribute::id, nuevo)),
                "El atributo");
    }

    private CanonicalModel borrarAtributo(CanonicalModel modelo, String attrId) {
        return mapearClaseQueContiene(modelo, attrId, Attribute::id, ClassEntity::attributes,
                c -> conAtributos(c, quitar(c.attributes(), attrId, Attribute::id, "El atributo")),
                "El atributo");
    }

    // ---------------------------------------------------------------- métodos

    private CanonicalModel reemplazarMetodo(
            CanonicalModel modelo, String methodId, Map<String, Object> datos) {
        Method nuevo = convertir(datos, Method.class);
        exigirMismoId(methodId, nuevo.id(), "método");
        return mapearClaseQueContiene(modelo, methodId, Method::id, ClassEntity::methods,
                c -> conMetodos(c, reemplazar(c.methods(), methodId, Method::id, nuevo)),
                "El método");
    }

    private CanonicalModel borrarMetodo(CanonicalModel modelo, String methodId) {
        return mapearClaseQueContiene(modelo, methodId, Method::id, ClassEntity::methods,
                c -> conMetodos(c, quitar(c.methods(), methodId, Method::id, "El método")),
                "El método");
    }

    // ------------------------------------------------------------- utilitarios

    private <T> CanonicalModel mapearClaseQueContiene(
            CanonicalModel modelo,
            String hijoId,
            java.util.function.Function<T, String> idDelHijo,
            java.util.function.Function<ClassEntity, List<T>> hijos,
            UnaryOperator<ClassEntity> transformacion,
            String queEs) {

        ClassEntity duena = modelo.classes().stream()
                .filter(c -> hijos.apply(c).stream().anyMatch(h -> hijoId.equals(idDelHijo.apply(h))))
                .findFirst()
                .orElseThrow(() -> MutacionRechazadaException.objetivoInexistente(queEs, hijoId));

        return conClases(modelo, reemplazar(
                modelo.classes(), duena.id(), ClassEntity::id, transformacion.apply(duena)));
    }

    private CanonicalModel mapearClase(
            CanonicalModel modelo, String classId, UnaryOperator<ClassEntity> transformacion) {
        ClassEntity actual = buscar(modelo.classes(), classId, ClassEntity::id, "La clase");
        return conClases(
                modelo,
                reemplazar(modelo.classes(), classId, ClassEntity::id, transformacion.apply(actual)));
    }

    private CanonicalModel mapearRelacion(
            CanonicalModel modelo, String relId, UnaryOperator<Relationship> transformacion) {
        Relationship actual = buscar(modelo.relationships(), relId, Relationship::id, "La relación");
        return conRelaciones(modelo, reemplazar(
                modelo.relationships(), relId, Relationship::id, transformacion.apply(actual)));
    }

    private CanonicalModel mapearPaquete(
            CanonicalModel modelo, String pkgId, UnaryOperator<Package> transformacion) {
        Package actual = buscar(modelo.packages(), pkgId, Package::id, "El paquete");
        return conPaquetes(modelo, reemplazar(
                modelo.packages(), pkgId, Package::id, transformacion.apply(actual)));
    }

    private static <T> T buscar(
            List<T> lista, String id, java.util.function.Function<T, String> idDe, String queEs) {
        if (id == null) {
            throw MutacionRechazadaException.payloadInvalido(
                    "La operación necesita un targetId y vino vacío");
        }
        return lista.stream()
                .filter(e -> id.equals(idDe.apply(e)))
                .findFirst()
                .orElseThrow(() -> MutacionRechazadaException.objetivoInexistente(queEs, id));
    }

    private static <T> List<T> agregar(
            List<T> lista, T nuevo, java.util.function.Function<T, String> idDe) {
        String id = idDe.apply(nuevo);
        if (id == null || id.isBlank()) {
            throw MutacionRechazadaException.payloadInvalido("El elemento nuevo no trae id");
        }
        if (lista.stream().anyMatch(e -> id.equals(idDe.apply(e)))) {
            // El cliente genera los ids, así que un duplicado significa que reenvió algo ya
            // aplicado sin clientMutationId, o que hay una colisión real. En ninguno de los dos
            // casos hay que insertarlo dos veces.
            throw new MutacionRechazadaException(
                    MutacionRechazadaException.ID_DUPLICADO, 409,
                    "Ya existe un elemento con el id " + id);
        }
        List<T> copia = new ArrayList<>(lista);
        copia.add(nuevo);
        return List.copyOf(copia);
    }

    private static <T> List<T> reemplazar(
            List<T> lista, String id, java.util.function.Function<T, String> idDe, T nuevo) {
        return lista.stream().map(e -> id.equals(idDe.apply(e)) ? nuevo : e).toList();
    }

    private static <T> List<T> quitar(
            List<T> lista, String id, java.util.function.Function<T, String> idDe, String queEs) {
        buscar(lista, id, idDe, queEs); // falla explícito si no existe, en vez de no borrar nada
        return lista.stream().filter(e -> !id.equals(idDe.apply(e))).toList();
    }

    private static void exigirMismoId(String targetId, String idDelPayload, String queEs) {
        if (idDelPayload != null && !idDelPayload.equals(targetId)) {
            throw MutacionRechazadaException.payloadInvalido(
                    "El id del " + queEs + " en el payload (" + idDelPayload
                            + ") no coincide con el targetId (" + targetId + ")");
        }
    }

    private static CanonicalModel conClases(CanonicalModel m, List<ClassEntity> clases) {
        return new CanonicalModel(
                m.schemaVersion(), m.mutationVersion(), m.packages(), clases, m.relationships());
    }

    private static CanonicalModel conRelaciones(CanonicalModel m, List<Relationship> relaciones) {
        return new CanonicalModel(
                m.schemaVersion(), m.mutationVersion(), m.packages(), m.classes(), relaciones);
    }

    private static CanonicalModel conPaquetes(CanonicalModel m, List<Package> paquetes) {
        return new CanonicalModel(
                m.schemaVersion(), m.mutationVersion(), paquetes, m.classes(), m.relationships());
    }

    private static ClassEntity conAtributos(ClassEntity c, List<Attribute> atributos) {
        return new ClassEntity(c.id(), c.name(), c.visibility(), c.isAbstract(), c.position(),
                c.width(), c.height(), atributos, c.methods());
    }

    private static ClassEntity conMetodos(ClassEntity c, List<Method> metodos) {
        return new ClassEntity(c.id(), c.name(), c.visibility(), c.isAbstract(), c.position(),
                c.width(), c.height(), c.attributes(), metodos);
    }

    // ----------------------------------------------------- lectura del payload

    /**
     * Convierte el payload con el <b>mapper canónico</b>, no con uno cualquiera: así los defaults
     * de §7 se aplican igual que si el objeto hubiera entrado por REST, y un campo desconocido
     * falla acá en vez de guardarse a medias.
     */
    private <T> T convertir(Map<String, Object> datos, Class<T> tipo) {
        try {
            return objectMapper.convertValue(datos, tipo);
        } catch (IllegalArgumentException e) {
            throw MutacionRechazadaException.payloadInvalido(
                    "El payload no es un " + tipo.getSimpleName() + " válido: " + e.getMessage());
        }
    }

    private List<Waypoint> waypoints(Map<String, Object> datos) {
        Object crudo = datos.get("waypoints");
        if (crudo == null) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(
                    crudo, objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, Waypoint.class));
        } catch (IllegalArgumentException e) {
            throw MutacionRechazadaException.payloadInvalido(
                    "waypoints no es una lista de puntos válida: " + e.getMessage());
        }
    }

    private static double numero(Map<String, Object> datos, String campo) {
        Object valor = datos.get(campo);
        if (!(valor instanceof Number n)) {
            throw MutacionRechazadaException.payloadInvalido(
                    "El payload necesita un número en '" + campo + "' y trajo: " + valor);
        }
        return n.doubleValue();
    }

    private static String texto(Map<String, Object> datos, String campo) {
        Object valor = datos.get(campo);
        if (!(valor instanceof String s) || s.isBlank()) {
            throw MutacionRechazadaException.payloadInvalido(
                    "El payload necesita un texto no vacío en '" + campo + "'");
        }
        return s;
    }
}
