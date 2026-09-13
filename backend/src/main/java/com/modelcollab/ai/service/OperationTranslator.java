package com.modelcollab.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.CanonicalMutationApplier;
import com.modelcollab.collaboration.service.MutacionRechazadaException;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Convierte lo que propuso la IA en operaciones reales del catálogo de §8.1.
 *
 * <h2>Acá es donde la IA deja de ser especial</h2>
 *
 * <p>La salida de esta clase son {@link StompMutationMessage}, exactamente los mismos que manda
 * el lienzo cuando alguien arrastra una caja. De acá en adelante nadie sabe ni le importa que
 * vinieran de un comando de voz: pasan por el mismo servicio de mutación, consumen número de
 * secuencia, quedan en la bitácora de operaciones y se difunden a la sala.
 *
 * <p>El atajo sería escribir el JSONB directamente. Sería más corto y estaría mal: se saltearía
 * el número de secuencia —y entonces el rebase offline vería un hueco en la historia—, la
 * bitácora no tendría constancia de qué pasó, y los otros colaboradores no se enterarían hasta
 * recargar. <b>Una operación que no pasa por el camino común no existe para el resto del
 * sistema.</b>
 *
 * <h2>El ensayo en seco</h2>
 *
 * <p>Cada operación se aplica sobre una copia en memoria antes de tocar la base, y el modelo
 * resultante alimenta a la siguiente. Eso resuelve dos cosas de una: un comando puede crear una
 * clase y en la misma tanda agregarle un atributo, y si la operación 4 de 5 es imposible, el
 * comando entero se rechaza <b>sin haber aplicado las tres primeras</b>. Medio comando aplicado
 * es un diagrama que el usuario no pidió y que no sabe cómo deshacer.
 */
@Component
public class OperationTranslator {

    /** Una operación ya traducida, con la frase que se le va a mostrar al usuario. */
    public record Traduccion(StompMutationMessage mensaje, String descripcion) {}

    /** El resultado del ensayo en seco: qué se haría y cómo quedaría el diagrama. */
    public record Resultado(List<Traduccion> operaciones, CanonicalModel modeloResultante) {}

    /**
     * Claves que admite cada operación. Una clave de más significa que el modelo se desvió del
     * contrato: o entendió mal el esquema, o está intentando algo que no le corresponde. En los
     * dos casos se rechaza, porque aceptar lo que no se pidió es cómo se cuelan los datos que
     * después nadie sabe de dónde salieron.
     */
    private static final Map<String, Set<String>> CLAVES_ADMITIDAS = Map.of(
            "ADD_CLASS", Set.of("isAbstract"),
            "ADD_ATTRIBUTE", Set.of("name", "type", "isPrimaryKey", "isNullable"),
            "RENAME_CLASS", Set.of("name"),
            "MOVE_CLASS", Set.of("direction"),
            "ADD_RELATIONSHIP",
            Set.of("to", "type", "sourceMultiplicity", "targetMultiplicity"));

    private final CanonicalMutationApplier applier;
    private final GridLayoutEngine grilla;
    private final ObjectMapper objectMapper;

    public OperationTranslator(
            CanonicalMutationApplier applier,
            GridLayoutEngine grilla,
            ObjectMapper objectMapper) {
        this.applier = applier;
        this.grilla = grilla;
        this.objectMapper = objectMapper;
    }

    public Resultado traducir(CanonicalModel inicial, List<AiOperation> propuestas) {
        List<Traduccion> traducidas = new ArrayList<>();
        CanonicalModel modelo = inicial;
        // Celdas que van a ocupar las clases de ESTE comando y que todavía no están en el
        // modelo: sin esto, un comando que crea dos clases las apila en la misma celda.
        Set<String> celdasReservadas = new HashSet<>();

        for (AiOperation propuesta : propuestas) {
            comprobarClaves(propuesta);
            Traduccion traduccion = traducirUna(modelo, propuesta, celdasReservadas);
            traducidas.add(traduccion);
            modelo = ensayar(modelo, traduccion.mensaje());
        }
        return new Resultado(traducidas, modelo);
    }

    // ---------------------------------------------------------- una por una

    private Traduccion traducirUna(
            CanonicalModel modelo, AiOperation propuesta, Set<String> celdasReservadas) {

        return switch (propuesta.op()) {
            case "ADD_CLASS" -> agregarClase(modelo, propuesta, celdasReservadas);
            case "ADD_ATTRIBUTE" -> agregarAtributo(modelo, propuesta);
            case "RENAME_CLASS" -> renombrarClase(modelo, propuesta);
            case "MOVE_CLASS" -> moverClase(modelo, propuesta);
            case "ADD_RELATIONSHIP" -> agregarRelacion(modelo, propuesta);
            // Inalcanzable: el parser ya filtró contra la lista blanca. Está para que, si
            // alguien agrega una operación a la lista y se olvida de traducirla, falle acá y
            // no la deje pasar sin efecto.
            default -> throw contratoRoto(
                    "La operación " + propuesta.op() + " está permitida pero no traducida");
        };
    }

    private Traduccion agregarClase(
            CanonicalModel modelo, AiOperation propuesta, Set<String> celdasReservadas) {

        String nombre = propuesta.target().strip();
        if (buscarPorNombre(modelo, nombre) != null) {
            throw new ComandoRechazadoException(
                    AiCommandResponse.ROMPERIA_EL_DIAGRAMA,
                    "Ya existe una clase llamada '" + nombre + "'. Dos clases con el mismo "
                            + "nombre no se pueden generar como código.");
        }

        Position posicion = grilla.siguienteHueco(modelo, celdasReservadas);
        celdasReservadas.add(grilla.celdaDe(posicion));

        ClassEntity nueva = new ClassEntity(
                UUID.randomUUID().toString(), nombre, null,
                booleano(propuesta, "isAbstract", false),
                posicion, null, null, List.of(), List.of());

        return new Traduccion(
                mensaje(OperationType.ADD_CLASS, null, aMapa(nueva)),
                "Creé la clase '" + nombre + "'.");
    }

    private Traduccion agregarAtributo(CanonicalModel modelo, AiOperation propuesta) {
        ClassEntity clase = exigirClase(modelo, propuesta.target());
        String nombre = exigirTexto(propuesta, "name");

        boolean repetido = clase.attributes().stream()
                .anyMatch(a -> nombre.equalsIgnoreCase(a.name()));
        if (repetido) {
            throw new ComandoRechazadoException(
                    AiCommandResponse.ROMPERIA_EL_DIAGRAMA,
                    "'" + clase.name() + "' ya tiene un atributo llamado '" + nombre + "'.");
        }

        AttributeType tipo = tipoDeAtributo(exigirTexto(propuesta, "type"));
        Attribute atributo = new Attribute(
                UUID.randomUUID().toString(), nombre, tipo, null, null, null, null,
                booleano(propuesta, "isPrimaryKey", false),
                booleano(propuesta, "isNullable", true),
                null, null);

        return new Traduccion(
                mensaje(OperationType.ADD_ATTRIBUTE, clase.id(), aMapa(atributo)),
                "Agregué el atributo '" + nombre + "' (" + tipo + ") a '" + clase.name() + "'.");
    }

    private Traduccion renombrarClase(CanonicalModel modelo, AiOperation propuesta) {
        ClassEntity clase = exigirClase(modelo, propuesta.target());
        String nuevo = exigirTexto(propuesta, "name");

        ClassEntity choque = buscarPorNombre(modelo, nuevo);
        if (choque != null && !choque.id().equals(clase.id())) {
            throw new ComandoRechazadoException(
                    AiCommandResponse.ROMPERIA_EL_DIAGRAMA,
                    "Ya existe otra clase llamada '" + nuevo + "'.");
        }

        return new Traduccion(
                mensaje(OperationType.RENAME_CLASS, clase.id(), Map.of("name", nuevo)),
                "Renombré '" + clase.name() + "' a '" + nuevo + "'.");
    }

    private Traduccion moverClase(CanonicalModel modelo, AiOperation propuesta) {
        ClassEntity clase = exigirClase(modelo, propuesta.target());
        GridLayoutEngine.Direccion direccion = direccion(exigirTexto(propuesta, "direction"));

        Position destino = grilla.mover(modelo, clase, direccion);
        Position origen = clase.position() == null ? new Position(0, 0) : clase.position();

        // Si no se movió hay que decirlo. Un "listo, la moví" sobre una clase que está donde
        // estaba es peor que un error: el usuario cree que el sistema no le hace caso.
        String descripcion = destino.equals(origen)
                ? "'" + clase.name() + "' ya está en el borde " + haciaDonde(direccion)
                        + ": no se movió."
                : "Moví '" + clase.name() + "' " + haciaDonde(direccion) + ".";

        return new Traduccion(
                mensaje(OperationType.MOVE_CLASS, clase.id(),
                        Map.of("x", destino.x(), "y", destino.y())),
                descripcion);
    }

    private Traduccion agregarRelacion(CanonicalModel modelo, AiOperation propuesta) {
        ClassEntity origen = exigirClase(modelo, propuesta.target());
        ClassEntity destino = exigirClase(modelo, exigirTexto(propuesta, "to"));
        RelationshipType tipo = tipoDeRelacion(exigirTexto(propuesta, "type"));

        // La herencia y la dependencia no llevan multiplicidades: los fixtures de referencia las
        // traen en null y el generador tampoco las mira. Si el modelo las manda igual, se
        // descartan en vez de rechazar el comando — es ruido suyo, no un pedido del usuario.
        boolean llevaMultiplicidades =
                tipo != RelationshipType.INHERITANCE && tipo != RelationshipType.DEPENDENCY;

        Multiplicity desde = null;
        Multiplicity hasta = null;
        if (llevaMultiplicidades) {
            Multiplicity porDefectoOrigen = tipo == RelationshipType.MANY_TO_MANY
                    ? Multiplicity.ZERO_MANY : Multiplicity.ONE_ONE;
            Multiplicity porDefectoDestino = Multiplicity.ZERO_MANY;
            desde = multiplicidad(propuesta, "sourceMultiplicity", porDefectoOrigen);
            hasta = multiplicidad(propuesta, "targetMultiplicity", porDefectoDestino);
        }

        Relationship relacion = new Relationship(
                UUID.randomUUID().toString(), origen.id(), destino.id(), tipo,
                null, null, desde, hasta, null, null, null, List.of());

        String comoQueda = llevaMultiplicidades
                ? " (" + tipo + ", " + desde.getValue() + " → " + hasta.getValue() + ")"
                : " (" + tipo + ")";
        return new Traduccion(
                mensaje(OperationType.ADD_RELATIONSHIP, null, aMapa(relacion)),
                "Relacioné '" + origen.name() + "' con '" + destino.name() + "'" + comoQueda + ".");
    }

    // ------------------------------------------------------------ ensayo seco

    /**
     * Aplica la operación a una copia en memoria. El aplicador es una función pura, así que esto
     * no toca nada: es exactamente el mismo código que va a correr de verdad después, que es lo
     * que hace que el ensayo signifique algo. Un ensayo con reglas distintas a las de la ejecución
     * no prueba nada sobre la ejecución.
     */
    private CanonicalModel ensayar(CanonicalModel modelo, StompMutationMessage mensaje) {
        try {
            return applier.apply(
                    modelo,
                    OperationType.valueOf(mensaje.op()),
                    mensaje.targetId(),
                    mensaje.payload());
        } catch (MutacionRechazadaException e) {
            // Llegar acá significa que el traductor dio por buena una operación que el aplicador
            // rechaza. Es un desvío del contrato que no habíamos previsto, no un error del
            // usuario, y por eso cuenta como falla del servicio de arriba.
            throw contratoRoto(
                    "El asistente propuso algo que el diagrama no acepta: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ utilitarios

    private void comprobarClaves(AiOperation propuesta) {
        Set<String> admitidas = CLAVES_ADMITIDAS.getOrDefault(propuesta.op(), Set.of());
        for (String clave : propuesta.args().keySet()) {
            if (!admitidas.contains(clave)) {
                throw contratoRoto("La operación " + propuesta.op()
                        + " no admite el campo '" + clave + "'");
            }
        }
    }

    /** Búsqueda por nombre sin distinguir mayúsculas: el usuario dicta, no transcribe. */
    private static ClassEntity buscarPorNombre(CanonicalModel modelo, String nombre) {
        if (nombre == null) {
            return null;
        }
        String buscado = nombre.strip();
        return modelo.classes().stream()
                .filter(c -> c.name() != null && c.name().strip().equalsIgnoreCase(buscado))
                .findFirst()
                .orElse(null);
    }

    private static ClassEntity exigirClase(CanonicalModel modelo, String nombre) {
        ClassEntity clase = buscarPorNombre(modelo, nombre);
        if (clase == null) {
            // Rechazo, no falla del servicio: lo más común es que el usuario se acuerde mal del
            // nombre, y lo único útil que se le puede decir es cuál nombró y que no está.
            throw new ComandoRechazadoException(
                    AiCommandResponse.OBJETIVO_INEXISTENTE,
                    "No encontré ninguna clase llamada '" + nombre + "' en este diagrama.");
        }
        return clase;
    }

    private static String exigirTexto(AiOperation propuesta, String clave) {
        String valor = propuesta.texto(clave);
        if (valor == null || valor.isBlank()) {
            throw contratoRoto(
                    "La operación " + propuesta.op() + " no trae el campo '" + clave + "'");
        }
        return valor.strip();
    }

    private static boolean booleano(AiOperation propuesta, String clave, boolean porDefecto) {
        Object valor = propuesta.args().get(clave);
        if (valor == null) {
            return porDefecto;
        }
        if (valor instanceof Boolean b) {
            return b;
        }
        String texto = String.valueOf(valor).strip().toLowerCase(Locale.ROOT);
        if (texto.equals("true") || texto.equals("false")) {
            return texto.equals("true");
        }
        throw contratoRoto("El campo '" + clave + "' tiene que ser true o false, y vino: " + valor);
    }

    private static AttributeType tipoDeAtributo(String texto) {
        try {
            return AttributeType.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // El caso que el planificador puso como ejemplo: un tipo inventado. Si entrara, la
            // clase quedaría en el canónico con un tipo que el generador no sabe traducir, y el
            // síntoma aparecería como un ZIP que no compila.
            throw contratoRoto("'" + texto + "' no es un tipo de atributo. Los tipos válidos son: "
                    + nombresDe(AttributeType.values()));
        }
    }

    private static RelationshipType tipoDeRelacion(String texto) {
        try {
            return RelationshipType.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw contratoRoto("'" + texto + "' no es un tipo de relación. Los válidos son: "
                    + nombresDe(RelationshipType.values()));
        }
    }

    private static Multiplicity multiplicidad(
            AiOperation propuesta, String clave, Multiplicity porDefecto) {
        String valor = propuesta.texto(clave);
        if (valor == null || valor.isBlank()) {
            return porDefecto;
        }
        try {
            return Multiplicity.fromValue(valor.strip());
        } catch (IllegalArgumentException e) {
            throw contratoRoto("'" + valor + "' no es una multiplicidad válida. Las válidas son: "
                    + nombresDe(Multiplicity.values()));
        }
    }

    private static GridLayoutEngine.Direccion direccion(String texto) {
        try {
            return GridLayoutEngine.Direccion.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw contratoRoto("'" + texto + "' no es una dirección. Las válidas son: "
                    + nombresDe(GridLayoutEngine.Direccion.values()));
        }
    }

    private static String haciaDonde(GridLayoutEngine.Direccion direccion) {
        return switch (direccion) {
            case LEFT -> "a la izquierda";
            case RIGHT -> "a la derecha";
            case UP -> "hacia arriba";
            case DOWN -> "hacia abajo";
        };
    }

    private static String nombresDe(Object[] valores) {
        StringBuilder sb = new StringBuilder();
        for (Object valor : valores) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(valor instanceof Multiplicity m ? m.getValue() : valor.toString());
        }
        return sb.toString();
    }

    private static StompMutationMessage mensaje(
            OperationType op, String targetId, Map<String, Object> payload) {
        // El clientMutationId lo pone AiCommandService, que es el que conoce el id del comando
        // entero y el número de esta operación dentro de él.
        return new StompMutationMessage(op.name(), targetId, null, payload);
    }

    private Map<String, Object> aMapa(Object registro) {
        Map<String, Object> mapa =
                objectMapper.convertValue(registro, new TypeReference<Map<String, Object>>() {});
        return new LinkedHashMap<>(mapa);
    }

    private static RespuestaDeIaInvalidaException contratoRoto(String mensaje) {
        return new RespuestaDeIaInvalidaException(AiCommandResponse.CONTRATO_ROTO, mensaje);
    }
}
