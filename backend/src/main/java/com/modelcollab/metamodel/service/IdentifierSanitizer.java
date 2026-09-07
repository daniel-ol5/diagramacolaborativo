package com.modelcollab.metamodel.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Convierte los nombres que escribe un modelador en identificadores válidos de Java y de SQL
 * (§11.2.1).
 *
 * <h2>Son dos listas independientes, no una</h2>
 *
 * <p>Es el error que hace el generador ingenuo, y {@code edge-cases.json} lo ejercita a propósito:
 *
 * <table border="1">
 *   <caption>Los dos conjuntos no se solapan</caption>
 *   <tr><th>Identificador</th><th>En Java</th><th>En SQL</th></tr>
 *   <tr><td>{@code Order}, {@code Group}, {@code Table}</td>
 *       <td>válidos como nombre de clase</td>
 *       <td><b>reservados</b>: la tabla necesita comillas</td></tr>
 *   <tr><td>{@code class}, {@code public}, {@code int}</td>
 *       <td><b>reservados</b>: el campo no compila</td>
 *       <td>válidos como nombre de columna</td></tr>
 * </table>
 *
 * <p>Con una sola lista se rompe por los dos lados a la vez: o se rechazan nombres de clase
 * perfectamente legales, o se genera DDL que no se puede ejecutar. Por eso cada método sabe
 * <b>para qué capa</b> está saneando, y no hay ningún método genérico de "sanear un nombre".
 *
 * <p>La estrategia por capa, textual de §11.2.1:
 * <ul>
 *   <li><b>SQL:</b> se escapa el identificador entre comillas dobles y <b>se conserva el nombre
 *       original en Java</b>. La clase se sigue llamando {@code Order}.</li>
 *   <li><b>Java:</b> se antepone un guion bajo ({@code _class}) y se preserva el nombre real de
 *       la columna con {@code @Column(name = "class")}, de modo que la base respeta el modelo y
 *       el código compila.</li>
 * </ul>
 */
@Component
public class IdentifierSanitizer {

    /**
     * Palabras reservadas de Java (JLS §3.9) más los tres literales, que tampoco pueden ser
     * identificadores. No incluye las contextuales ({@code var}, {@code record}, {@code sealed},
     * {@code yield}, {@code permits}): son válidas como nombre de campo y prefijarlas sería
     * ensuciar el código generado sin motivo.
     */
    private static final Set<String> RESERVADAS_JAVA = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null");

    /**
     * Palabras reservadas de PostgreSQL que no se pueden usar como nombre de tabla o columna sin
     * comillas. Es la lista de <i>reserved</i> del manual; las <i>non-reserved</i> quedan fuera a
     * propósito, porque escaparlas de más produce identificadores sensibles a mayúsculas que
     * después obligan a citarlos en cada consulta escrita a mano.
     *
     * <p>Por eso {@code class} <b>no</b> está acá: es reservada de Java pero no de PostgreSQL, y
     * el ejemplo de §11.2.1 la muestra justamente sin comillas.
     */
    private static final Set<String> RESERVADAS_SQL = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both",
            "case", "cast", "check", "collate", "column", "constraint", "create", "current_catalog",
            "current_date", "current_role", "current_time", "current_timestamp", "current_user",
            "default", "deferrable", "desc", "distinct", "do", "else", "end", "except", "false",
            "fetch", "for", "foreign", "from", "grant", "group", "having", "in", "initially",
            "intersect", "into", "lateral", "leading", "limit", "localtime", "localtimestamp",
            "not", "null", "offset", "on", "only", "or", "order", "placing", "primary",
            "references", "returning", "select", "session_user", "some", "symmetric", "table",
            "then", "to", "trailing", "true", "union", "unique", "user", "using", "variadic",
            "when", "where", "window", "with",
            // Tipos y palabras que PostgreSQL clasifica aparte pero que rompen igual sin comillas.
            "int", "integer", "float", "double", "decimal", "numeric", "char", "varchar", "text",
            "boolean", "date", "time", "timestamp", "interval", "binary", "authorization",
            "between", "cross", "freeze", "full", "ilike", "inner", "is", "isnull", "join", "left",
            "like", "natural", "notnull", "outer", "overlaps", "right", "similar", "verbose");

    // ------------------------------------------------------------------- Java

    /**
     * Nombre de clase Java: PascalCase. <b>No</b> se toca por ser reservada de SQL — {@code Order}
     * sigue siendo {@code Order} en el código, que es de lo que se trata §11.2.1(a).
     */
    public String nombreDeClase(String original) {
        String limpio = soloLetrasYDigitos(original);
        if (limpio.isEmpty()) {
            return "Entidad";
        }
        String pascal = aPascalCase(limpio);
        // Una clase no puede llamarse como una reservada de Java. Es rarísimo (habría que
        // escribir "int" con mayúscula inicial y aun así no colisiona), pero un identificador
        // que empieza con dígito sí es un caso real: "2Fase" no compila.
        if (Character.isDigit(pascal.charAt(0))) {
            pascal = "_" + pascal;
        }
        return RESERVADAS_JAVA.contains(pascal.toLowerCase(Locale.ROOT)) && esMinuscula(pascal)
                ? "_" + pascal
                : pascal;
    }

    /**
     * Nombre de campo Java: camelCase, con guion bajo delante si choca con una palabra reservada
     * (§11.2.1(b)). El nombre real de la columna se preserva aparte con {@code @Column}.
     */
    public String nombreDeCampo(String original) {
        String limpio = soloLetrasYDigitos(original);
        if (limpio.isEmpty()) {
            return "campo";
        }
        String camel = aCamelCase(limpio);
        if (Character.isDigit(camel.charAt(0)) || esReservadaDeJava(camel)) {
            return "_" + camel;
        }
        return camel;
    }

    /** Getter/setter a partir del nombre de campo ya saneado: {@code _class} -> {@code get_class}. */
    public String enMayusculaInicial(String campo) {
        return campo.isEmpty() ? campo : Character.toUpperCase(campo.charAt(0)) + campo.substring(1);
    }

    public boolean esReservadaDeJava(String identificador) {
        return identificador != null && RESERVADAS_JAVA.contains(identificador);
    }

    // -------------------------------------------------------------------- SQL

    /**
     * Nombre de tabla listo para {@code @Table(name = …)}: snake_case, entre comillas dobles si
     * es reservada de PostgreSQL.
     */
    public String nombreDeTabla(String nombreDeClase) {
        return escaparSiHaceFalta(aSnakeCase(soloLetrasYDigitos(nombreDeClase)));
    }

    /** Nombre de columna listo para {@code @Column(name = …)}, con la misma regla. */
    public String nombreDeColumna(String nombreDeAtributo) {
        return escaparSiHaceFalta(aSnakeCase(soloLetrasYDigitos(nombreDeAtributo)));
    }

    /** Nombre de tabla intermedia de un MANY_TO_MANY, con la misma regla. */
    public String nombreDeTablaIntermedia(String origen, String destino) {
        return escaparSiHaceFalta(
                aSnakeCase(soloLetrasYDigitos(origen)) + "_" + aSnakeCase(soloLetrasYDigitos(destino)));
    }

    public boolean esReservadaDeSql(String identificador) {
        return identificador != null && RESERVADAS_SQL.contains(identificador.toLowerCase(Locale.ROOT));
    }

    private String escaparSiHaceFalta(String snake) {
        if (snake.isEmpty()) {
            return "campo";
        }
        // Las comillas van en el valor, no en el código: el generador después lo convierte en un
        // literal Java escapado, que es lo que produce @Table(name = "\"order\"").
        return esReservadaDeSql(snake) ? "\"" + snake + "\"" : snake;
    }

    // --------------------------------------------------------------- formatos

    /**
     * Deja sólo letras y dígitos ASCII, separando por espacios lo que venía pegado con guiones,
     * puntos o acentos. Un nombre como {@code "Detalle de Órden"} tiene que poder convertirse en
     * algo compilable sin que el modelador tenga que saber las reglas de Java.
     */
    private static String soloLetrasYDigitos(String original) {
        if (original == null) {
            return "";
        }
        String sinAcentos = java.text.Normalizer
                .normalize(original, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinAcentos.replaceAll("[^A-Za-z0-9]+", " ").trim();
    }

    private static String aPascalCase(String limpio) {
        StringBuilder sb = new StringBuilder();
        for (String palabra : limpio.split(" ")) {
            if (!palabra.isEmpty()) {
                sb.append(Character.toUpperCase(palabra.charAt(0))).append(palabra.substring(1));
            }
        }
        return sb.toString();
    }

    private static String aCamelCase(String limpio) {
        String pascal = aPascalCase(limpio);
        if (pascal.isEmpty()) {
            return pascal;
        }
        // Sólo se baja la primera letra si la siguiente no es mayúscula: así "IVA" no se
        // convierte en "iVA", que es feo y confunde al leer el código generado.
        if (pascal.length() > 1 && Character.isUpperCase(pascal.charAt(1))) {
            return pascal;
        }
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    private static String aSnakeCase(String limpio) {
        String conGuiones = limpio.replace(" ", "_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conGuiones.length(); i++) {
            char c = conGuiones.charAt(i);
            boolean anteriorEsLetraMinuscula =
                    i > 0 && Character.isLowerCase(conGuiones.charAt(i - 1));
            if (Character.isUpperCase(c) && anteriorEsLetraMinuscula) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString().replaceAll("_+", "_");
    }

    private static boolean esMinuscula(String s) {
        return s.equals(s.toLowerCase(Locale.ROOT));
    }
}
