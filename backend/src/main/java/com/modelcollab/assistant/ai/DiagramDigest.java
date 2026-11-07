package com.modelcollab.assistant.ai;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Package;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resumen en texto compacto (en español) del modelo canónico, para dárselo a la IA del chat
 * como contexto (CU20). Clase pura: no consulta nada y no modifica el modelo.
 *
 * <p>Formato:
 *
 * <pre>
 * Clases (2):
 * - Persona [abstracta] (atributos: id:UUID (PK), nombre:VARCHAR; métodos: saludar)
 * - Cliente (atributos: id:UUID (PK))
 * Relaciones (1):
 * - Cliente -&gt; Persona (herencia)
 * Paquetes:
 * - personas: Persona, Cliente
 * </pre>
 *
 * <p><b>Tope de {@value #TOPE} caracteres.</b> Si se pasa, se recorta por niveles: primero se
 * quitan los métodos, después los atributos y después los paquetes; los nombres de clase y las
 * relaciones se conservan. Si ni así cabe, la lista de relaciones se acota a la mitad del tope y
 * la de clases a lo que quede, con "… y N relaciones más" / "… y N clases más".
 *
 * <p>Los nombres son texto de usuarios: se quitan los saltos de línea y tabuladores (para que un
 * nombre no fabrique líneas nuevas), se acotan a {@value #MAX_NOMBRE} caracteres y se neutralizan
 * las secuencias de 3 o más {@code <} o {@code >}, igual que en el hilo del chat. El builder
 * vuelve a neutralizar al meter el digest en su bloque; hacerlo dos veces no cambia nada.
 */
public final class DiagramDigest {

    public static final int TOPE = 3000;
    public static final String VACIO = "El diagrama está vacío.";
    static final int MAX_NOMBRE = 60;
    static final String DESCONOCIDA = "(clase desconocida)";

    private DiagramDigest() {}

    /** Nivel de detalle de cada línea de clase. */
    private enum Detalle { COMPLETO, SIN_METODOS, SOLO_NOMBRES }

    public static String of(CanonicalModel modelo) {
        if (modelo == null || modelo.classes().isEmpty()) {
            return VACIO;
        }
        Map<String, String> nombres = new HashMap<>();
        for (ClassEntity c : modelo.classes()) {
            if (c != null && c.id() != null) {
                nombres.putIfAbsent(c.id(), nombre(c.name()));
            }
        }
        List<String> relaciones = new ArrayList<>();
        for (Relationship r : modelo.relationships()) {
            if (r != null) {
                relaciones.add(relacion(r, nombres));
            }
        }
        List<String> paquetes = new ArrayList<>();
        for (Package p : modelo.packages()) {
            if (p != null) {
                paquetes.add(paquete(p, nombres));
            }
        }
        List<ClassEntity> clases = modelo.classes().stream().filter(Objects::nonNull).toList();

        for (Detalle detalle : Detalle.values()) {
            String texto = armar(clases, detalle, relaciones, paquetes);
            if (texto.length() <= TOPE) {
                return texto;
            }
        }
        String sinPaquetes = armar(clases, Detalle.SOLO_NOMBRES, relaciones, List.of());
        if (sinPaquetes.length() <= TOPE) {
            return sinPaquetes;
        }
        return acotado(clases, relaciones);
    }

    // ---------------------------------------------------------------- armado

    private static String armar(
            List<ClassEntity> clases, Detalle detalle, List<String> relaciones, List<String> paquetes) {
        StringBuilder s = new StringBuilder();
        s.append("Clases (").append(clases.size()).append("):\n");
        for (ClassEntity c : clases) {
            s.append("- ").append(clase(c, detalle)).append('\n');
        }
        seccionRelaciones(s, relaciones);
        if (!paquetes.isEmpty()) {
            s.append("Paquetes:\n");
            paquetes.forEach(p -> s.append("- ").append(p).append('\n'));
        }
        return s.toString().stripTrailing();
    }

    /** Último recurso: relaciones hasta la mitad del tope y clases (solo nombres) en el resto. */
    private static String acotado(List<ClassEntity> clases, List<String> relaciones) {
        StringBuilder rel = new StringBuilder();
        rel.append("Relaciones (").append(relaciones.size()).append("):\n");
        int mitad = TOPE / 2;
        for (int i = 0; i < relaciones.size(); i++) {
            String linea = "- " + relaciones.get(i) + "\n";
            String resto = "… y " + (relaciones.size() - i) + " relaciones más\n";
            boolean ultima = i == relaciones.size() - 1;
            if (rel.length() + linea.length() + (ultima ? 0 : resto.length()) > mitad) {
                rel.append(resto);
                break;
            }
            rel.append(linea);
        }
        if (relaciones.isEmpty()) {
            rel.setLength(0);
        }

        StringBuilder s = new StringBuilder();
        s.append("Clases (").append(clases.size()).append("):\n");
        int disponible = TOPE - rel.length();
        for (int i = 0; i < clases.size(); i++) {
            String linea = "- " + clase(clases.get(i), Detalle.SOLO_NOMBRES) + "\n";
            String resto = "… y " + (clases.size() - i) + " clases más\n";
            boolean ultima = i == clases.size() - 1;
            if (s.length() + linea.length() + (ultima ? 0 : resto.length()) > disponible) {
                s.append(resto);
                break;
            }
            s.append(linea);
        }
        s.append(rel);
        return s.toString().stripTrailing();
    }

    private static void seccionRelaciones(StringBuilder s, List<String> relaciones) {
        if (relaciones.isEmpty()) {
            return;
        }
        s.append("Relaciones (").append(relaciones.size()).append("):\n");
        relaciones.forEach(r -> s.append("- ").append(r).append('\n'));
    }

    // ----------------------------------------------------------------- líneas

    private static String clase(ClassEntity c, Detalle detalle) {
        StringBuilder s = new StringBuilder(nombre(c.name()));
        if (Boolean.TRUE.equals(c.isAbstract())) {
            s.append(" [abstracta]");
        }
        if (detalle == Detalle.SOLO_NOMBRES) {
            return s.toString();
        }
        List<String> partes = new ArrayList<>();
        List<String> atributos = c.attributes().stream()
                .filter(Objects::nonNull).map(DiagramDigest::atributo).toList();
        if (!atributos.isEmpty()) {
            partes.add("atributos: " + String.join(", ", atributos));
        }
        if (detalle == Detalle.COMPLETO) {
            List<String> metodos = c.methods().stream()
                    .filter(Objects::nonNull).map(Method::name).map(DiagramDigest::nombre).toList();
            if (!metodos.isEmpty()) {
                partes.add("métodos: " + String.join(", ", metodos));
            }
        }
        if (!partes.isEmpty()) {
            s.append(" (").append(String.join("; ", partes)).append(')');
        }
        return s.toString();
    }

    private static String atributo(Attribute a) {
        String tipo = a.type() == null ? "?" : a.type().name();
        String texto = nombre(a.name()) + ":" + tipo;
        return Boolean.TRUE.equals(a.isPrimaryKey()) ? texto + " (PK)" : texto;
    }

    private static String relacion(Relationship r, Map<String, String> nombres) {
        String origen = nombres.getOrDefault(r.sourceClassId(), DESCONOCIDA);
        String destino = nombres.getOrDefault(r.targetClassId(), DESCONOCIDA);
        StringBuilder s = new StringBuilder(origen).append(" -> ").append(destino)
                .append(" (").append(tipo(r.type()));
        if (r.sourceMultiplicity() != null || r.targetMultiplicity() != null) {
            s.append(", ").append(multiplicidad(r.sourceMultiplicity()))
                    .append(" a ").append(multiplicidad(r.targetMultiplicity()));
        }
        return s.append(')').toString();
    }

    private static String paquete(Package p, Map<String, String> nombres) {
        List<String> miembros = p.classIds().stream()
                .map(id -> nombres.getOrDefault(id, DESCONOCIDA)).toList();
        return nombre(p.name()) + ": " + (miembros.isEmpty() ? "(sin clases)" : String.join(", ", miembros));
    }

    private static String tipo(RelationshipType t) {
        if (t == null) {
            return "relación";
        }
        return switch (t) {
            case ASSOCIATION -> "asociación";
            case AGGREGATION -> "agregación";
            case COMPOSITION -> "composición";
            case INHERITANCE -> "herencia";
            case DEPENDENCY -> "dependencia";
            case MANY_TO_MANY -> "muchos a muchos";
        };
    }

    private static String multiplicidad(Multiplicity m) {
        return m == null ? "?" : m.getValue();
    }

    /** Nombre de usuario en una sola línea, acotado y neutralizado. */
    static String nombre(String texto) {
        if (texto == null || texto.isBlank()) {
            return "(sin nombre)";
        }
        String limpio = texto.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\s+", " ").strip();
        if (limpio.length() > MAX_NOMBRE) {
            int fin = MAX_NOMBRE;
            if (Character.isHighSurrogate(limpio.charAt(fin - 1))) {
                fin--;
            }
            limpio = limpio.substring(0, fin) + "…";
        }
        return ConversationContextBuilder.neutralizar(limpio);
    }
}
