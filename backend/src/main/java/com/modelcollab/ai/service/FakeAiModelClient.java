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

    /** "Elimina el atributo telefono de Proveedor". */
    private static final Pattern ELIMINAR_ATRIBUTO = Pattern.compile(
            "(?:elimina|eliminá|eliminar|borra|borrá|borrar|quita|quitá|quitar)\\s+"
                    + "(?:el\\s+|un\\s+)?(?:atributo|campo)\\s+(\\w+)\\s+"
                    + "(?:de|en)\\s+(?:la\\s+)?(?:clase\\s+)?(\\w+)\\s*$",
            BANDERAS);

    /** "Elimina la clase Factura". */
    private static final Pattern ELIMINAR_CLASE = Pattern.compile(
            "(?:elimina|eliminá|eliminar|borra|borrá|borrar|quita|quitá|quitar)\\s+"
                    + "(?:la\\s+|una\\s+)?clase\\s+(\\w+)\\s*$",
            BANDERAS);

    /** Separa "total decimal, fecha date y cliente varchar". */
    private static final Pattern SEPARADOR_DE_ATRIBUTOS = Pattern.compile("\\s*,\\s*|\\s+y\\s+");

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

    private AiPlan interpretar(String comando) {
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

        Matcher delAttr = ELIMINAR_ATRIBUTO.matcher(comando);
        if (delAttr.matches()) {
            String nombre = delAttr.group(1);
            String clase = delAttr.group(2);
            return plan("Elimino el atributo " + nombre + " de " + clase + ".",
                    new AiOperation("DELETE_ATTRIBUTE", clase, Map.of("name", nombre)));
        }

        Matcher delClase = ELIMINAR_CLASE.matcher(comando);
        if (delClase.matches()) {
            String clase = delClase.group(1);
            return plan("Elimino la clase " + clase + ".",
                    new AiOperation("DELETE_CLASS", clase, Map.of()));
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

    private String serializar(AiPlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el plan simulado", e);
        }
    }
}
