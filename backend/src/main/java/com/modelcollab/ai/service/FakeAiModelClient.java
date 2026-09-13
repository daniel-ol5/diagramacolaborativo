package com.modelcollab.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.AiPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El modelo simulado: reglas y ranuras, sin red y sin clave.
 *
 * <h2>Para qué existe</h2>
 *
 * <p>La parte de CU16 que cuesta y que se defiende no es la llamada HTTP — es el guardarraíl, la
 * validación del contrato de salida y el mapeo a las operaciones de §8.1. Todo eso se construye y
 * se prueba sin tocar la red. Este cliente es lo que permite que el caso de uso <b>funcione de
 * punta a punta igual</b> mientras la clave de Gemini no llegue: se dicta un comando, aparece la
 * clase en el lienzo de los dos navegadores, y el rechazo educado sale cuando corresponde.
 *
 * <p>Si la clave nunca llega, lo que falta es la parte que nadie duda que funciona.
 *
 * <h2>Determinista, y eso es la mitad del valor</h2>
 *
 * <p>El mismo comando produce siempre el mismo plan. Eso permite que los tests afirmen
 * exactamente qué operaciones salen, en vez de conformarse con "salió algo". Es también, de
 * paso, la misma técnica que §10 define para la IA local del móvil en modo avión: reglas y
 * ranuras derivadas del metamodelo, sin modelo de lenguaje.
 *
 * <h2>Sus límites, dichos de frente</h2>
 *
 * <p>Entiende las formas de comando que enumera RF-02.2 a RF-02.4 y nada más. No generaliza, no
 * infiere sinónimos que no estén en las listas y no desambigua. Eso no es un defecto a esconder:
 * es la diferencia entre un modelo de lenguaje y un analizador de reglas, y es exactamente lo
 * que la clave de API viene a resolver. Lo que no cambia con la clave es todo lo que está
 * después de esta clase.
 */
public class FakeAiModelClient implements AiModelClient {

    private static final int BANDERAS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** "Crea la clase Factura con atributo total decimal y fecha date" (RF-02.2). */
    private static final Pattern CREAR_CLASE = Pattern.compile(
            "(?:crea|creá|créame|creame|crear|nueva|agrega|agregar|añade|anade)\\s+"
                    + "(?:una\\s+|la\\s+|el\\s+)?clase\\s+(?:llamada\\s+)?(\\w+)"
                    + "(?:\\s+(?:con|que\\s+tenga)\\s+(?:los\\s+|las\\s+|el\\s+|un\\s+|una\\s+)?"
                    + "(?:atributos?|campos?)\\s+(.+))?\\s*$",
            BANDERAS);

    /** "Agrega el atributo telefono varchar a Proveedor" (RF-02.3). */
    private static final Pattern AGREGAR_ATRIBUTO = Pattern.compile(
            "(?:agrega|agregá|agregar|añade|anade|pone|poné|poner)\\s+"
                    + "(?:el\\s+|un\\s+)?(?:atributo|campo)\\s+(\\w+)(?:\\s+(\\w+))?\\s+"
                    + "a\\s+(?:la\\s+)?(?:clase\\s+)?(\\w+)\\s*$",
            BANDERAS);

    /** "Renombra la clase Cliente a Comprador". */
    private static final Pattern RENOMBRAR = Pattern.compile(
            "(?:renombra|renombrá|renombrar|cambia|cambiá|cambiar)\\s+"
                    + "(?:el\\s+nombre\\s+de\\s+)?(?:la\\s+)?(?:clase\\s+)?(\\w+)\\s+"
                    + "(?:a|como|por)\\s+(\\w+)\\s*$",
            BANDERAS);

    /** "Mueve la clase Cliente a la derecha" (RF-02.3). */
    private static final Pattern MOVER = Pattern.compile(
            "(?:mueve|mové|mover|move|desplaza|desplazá)\\s+(?:la\\s+)?(?:clase\\s+)?(\\w+)\\s+"
                    + "(?:a\\s+la\\s+|hacia\\s+(?:la\\s+)?|para\\s+(?:la\\s+)?|al\\s+)?"
                    + "(derecha|izquierda|arriba|abajo)\\s*$",
            BANDERAS);

    /** "Relaciona Factura con Cliente de muchos a uno" (RF-02.4). */
    private static final Pattern RELACIONAR = Pattern.compile(
            "(?:relaciona|relacioná|relacionar|conecta|conectá|asocia|asociá)\\s+"
                    + "(?:la\\s+clase\\s+)?(\\w+)\\s+(?:con|y|a)\\s+(?:la\\s+clase\\s+)?(\\w+)"
                    + "(?:\\s+de\\s+(\\w+)\\s+a\\s+(\\w+))?\\s*$",
            BANDERAS);

    /** "Persona hereda de Cliente". */
    private static final Pattern HEREDAR = Pattern.compile(
            "(?:la\\s+clase\\s+)?(\\w+)\\s+hereda\\s+de\\s+(?:la\\s+clase\\s+)?(\\w+)\\s*$",
            BANDERAS);

    /** Separa "total decimal, fecha date y cliente varchar". */
    private static final Pattern SEPARADOR_DE_ATRIBUTOS = Pattern.compile("\\s*,\\s*|\\s+y\\s+");

    /**
     * Palabras de tipo. Sólo éstas: si la segunda palabra de un atributo no está acá, se asume
     * que el usuario no dijo el tipo y no que dijo uno raro — es más seguro caer en VARCHAR que
     * inventar un tipo a partir de una palabra que no reconocemos.
     */
    private static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("entero", "INTEGER"),
            Map.entry("int", "INTEGER"),
            Map.entry("integer", "INTEGER"),
            Map.entry("bigint", "BIGINT"),
            Map.entry("texto", "VARCHAR"),
            Map.entry("string", "VARCHAR"),
            Map.entry("cadena", "VARCHAR"),
            Map.entry("varchar", "VARCHAR"),
            Map.entry("text", "TEXT"),
            Map.entry("decimal", "DECIMAL"),
            Map.entry("real", "DECIMAL"),
            Map.entry("flotante", "DECIMAL"),
            Map.entry("double", "DECIMAL"),
            Map.entry("float", "DECIMAL"),
            Map.entry("booleano", "BOOLEAN"),
            Map.entry("bool", "BOOLEAN"),
            Map.entry("boolean", "BOOLEAN"),
            Map.entry("logico", "BOOLEAN"),
            Map.entry("fecha", "DATE"),
            Map.entry("date", "DATE"),
            Map.entry("datetime", "DATETIME"),
            Map.entry("timestamp", "DATETIME"),
            Map.entry("uuid", "UUID"),
            Map.entry("guid", "UUID"));

    private final CommandGuardrail guardarrail;
    private final ObjectMapper objectMapper;

    public FakeAiModelClient(CommandGuardrail guardarrail, ObjectMapper objectMapper) {
        this.guardarrail = guardarrail;
        this.objectMapper = objectMapper;
    }

    @Override
    public String responder(String comando, List<String> clasesDelDiagrama) {
        return serializar(interpretar(comando == null ? "" : comando.strip()));
    }

    @Override
    public String descripcion() {
        return "simulado (reglas locales, sin red)";
    }

    @Override
    public boolean esSimulado() {
        return true;
    }

    // ----------------------------------------------------------- las reglas

    private AiPlan interpretar(String comando) {
        // El mismo criterio que usa el servidor antes de llamar. Acá se repite a propósito: el
        // cliente tiene que comportarse como se comportaría el modelo de verdad, o los tests
        // que lo usan estarían probando un camino que en producción no existe.
        if (guardarrail.pideUnDominioEntero(comando)) {
            return new AiPlan(AiPlan.GENERAR_DOMINIO, CommandGuardrail.RECHAZO, List.of());
        }

        Matcher crear = CREAR_CLASE.matcher(comando);
        if (crear.matches()) {
            return crearClase(crear.group(1), crear.group(2));
        }

        Matcher atributo = AGREGAR_ATRIBUTO.matcher(comando);
        if (atributo.matches()) {
            String nombre = atributo.group(1);
            String tipo = tipoDe(atributo.group(2));
            String clase = atributo.group(3);
            return plan("Agrego el atributo " + nombre + " a " + clase + ".",
                    new AiOperation("ADD_ATTRIBUTE", clase,
                            Map.of("name", nombre, "type", tipo)));
        }

        Matcher renombrar = RENOMBRAR.matcher(comando);
        if (renombrar.matches()) {
            return plan("Renombro " + renombrar.group(1) + ".",
                    new AiOperation("RENAME_CLASS", renombrar.group(1),
                            Map.of("name", renombrar.group(2))));
        }

        Matcher mover = MOVER.matcher(comando);
        if (mover.matches()) {
            return plan("Muevo " + mover.group(1) + ".",
                    new AiOperation("MOVE_CLASS", mover.group(1),
                            Map.of("direction", direccion(mover.group(2)))));
        }

        Matcher heredar = HEREDAR.matcher(comando);
        if (heredar.matches()) {
            return plan(heredar.group(1) + " hereda de " + heredar.group(2) + ".",
                    new AiOperation("ADD_RELATIONSHIP", heredar.group(1),
                            Map.of("to", heredar.group(2), "type", "INHERITANCE")));
        }

        Matcher relacionar = RELACIONAR.matcher(comando);
        if (relacionar.matches()) {
            return relacionar(relacionar.group(1), relacionar.group(2),
                    relacionar.group(3), relacionar.group(4));
        }

        return new AiPlan(AiPlan.NO_SOPORTADO,
                "No supe traducir eso a una operación sobre el diagrama.", List.of());
    }

    private AiPlan crearClase(String nombre, String listaDeAtributos) {
        List<AiOperation> operaciones = new ArrayList<>();
        operaciones.add(new AiOperation("ADD_CLASS", nombre, Map.of()));

        if (listaDeAtributos != null && !listaDeAtributos.isBlank()) {
            for (String trozo : SEPARADOR_DE_ATRIBUTOS.split(listaDeAtributos.strip())) {
                String[] palabras = trozo.strip().split("\\s+");
                if (palabras.length == 0 || palabras[0].isBlank()) {
                    continue;
                }
                String tipo = tipoDe(palabras.length > 1 ? palabras[1] : null);
                operaciones.add(new AiOperation("ADD_ATTRIBUTE", nombre,
                        Map.of("name", palabras[0], "type", tipo)));
            }
        }
        // Sin recorte acá: si el comando produce más del tope, que lo rechace el tope. Un
        // cliente que se autolimita escondería que el guardarraíl funciona.
        return new AiPlan(AiPlan.EDITAR, "Creo la clase " + nombre + ".", operaciones);
    }

    private AiPlan relacionar(String origen, String destino, String desde, String hasta) {
        boolean muchosDeUnLado = esPlural(desde);
        boolean muchosDelOtro = esPlural(hasta);

        String tipo = muchosDeUnLado && muchosDelOtro ? "MANY_TO_MANY" : "ASSOCIATION";
        String multiplicidadOrigen = muchosDeUnLado ? "0..*" : "1..1";
        String multiplicidadDestino = muchosDelOtro ? "0..*" : "1..1";

        return plan("Relaciono " + origen + " con " + destino + ".",
                new AiOperation("ADD_RELATIONSHIP", origen, Map.of(
                        "to", destino,
                        "type", tipo,
                        "sourceMultiplicity", multiplicidadOrigen,
                        "targetMultiplicity", multiplicidadDestino)));
    }

    // ------------------------------------------------------------ utilitarios

    private static boolean esPlural(String palabra) {
        if (palabra == null) {
            return false;
        }
        String limpia = palabra.strip().toLowerCase(Locale.ROOT);
        return limpia.equals("muchos") || limpia.equals("muchas") || limpia.equals("varios")
                || limpia.equals("varias") || limpia.equals("n");
    }

    private static String tipoDe(String palabra) {
        if (palabra == null) {
            return "VARCHAR";
        }
        return TIPOS.getOrDefault(palabra.strip().toLowerCase(Locale.ROOT), "VARCHAR");
    }

    private static String direccion(String palabra) {
        return switch (palabra.strip().toLowerCase(Locale.ROOT)) {
            case "derecha" -> "RIGHT";
            case "izquierda" -> "LEFT";
            case "arriba" -> "UP";
            default -> "DOWN";
        };
    }

    private static AiPlan plan(String respuesta, AiOperation... operaciones) {
        return new AiPlan(AiPlan.EDITAR, respuesta, List.of(operaciones));
    }

    /**
     * El plan sale como texto, no como objeto, porque la interfaz devuelve texto crudo: este
     * cliente tiene que pasar por el mismo validador que pasaría la respuesta de Gemini. Si
     * devolviera el objeto ya armado, el camino que se prueba con él no sería el de producción.
     */
    private String serializar(AiPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el plan simulado", e);
        }
    }
}
