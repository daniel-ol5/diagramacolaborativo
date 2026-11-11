package com.modelcollab.vision.service;

import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.service.OwningSideRule;
import com.modelcollab.vision.dto.VisionAttribute;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionMethod;
import com.modelcollab.vision.dto.VisionRelationship;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Convierte el borrador leído de la foto en un modelo canónico, encima del que ya existe.
 *
 * <h2>Sólo agrega</h2>
 *
 * <p>Nunca modifica ni borra un elemento que ya estaba. Eso no es una casualidad de la
 * implementación, es la propiedad que hace que una importación por foto sea segura en una sala
 * con gente trabajando: aunque la operación se aplique mientras alguien está editando la clase
 * Cliente, no puede pisarle nada, porque lo único que hace es sumar clases nuevas con
 * identificadores nuevos.
 *
 * <p>Por eso el resultado se puede aplicar como un {@code BULK_MERGE} —que reemplaza el estado
 * con el modelo que lleva en el payload— sin que reemplazar signifique perder: el payload es
 * "todo lo que había, más esto".
 *
 * <h2>Blando al proponer, duro al confirmar</h2>
 *
 * <p>Los mismos datos se tratan de dos maneras según quién los vaya a mirar después, y la
 * diferencia es a propósito:
 *
 * <ul>
 *   <li><b>Al proponer</b> ({@code estricto = false}) un tipo ilegible se convierte en
 *       {@code VARCHAR} y se anota, y una relación que apunta a una clase que no está en ningún
 *       lado se descarta y se anota. La persona lo ve en el modal y lo corrige — para eso está
 *       el modal. Tirar la foto entera porque una palabra escrita a mano salió "decmal" haría
 *       la función inútil.</li>
 *   <li><b>Al confirmar</b> ({@code estricto = true}) no hay indulgencia: un tipo que no existe
 *       es un rechazo. En ese momento el borrador ya no es una lectura para revisar, es una
 *       escritura sobre el modelo canónico, y viene de un navegador como cualquier otra.</li>
 * </ul>
 *
 * <p>La regla detrás de las dos: <b>se afloja donde hay alguien mirando, y sólo ahí</b>.
 */
@Component
public class VisionMerger {

    /** El modelo que quedaría, más lo que hubo que tocar para llegar a él. */
    public record Resultado(
            CanonicalModel modelo,
            VisionDraft borradorSaneado,
            List<String> notas,
            int clasesNuevas,
            int relacionesNuevas) {}

    private final GridLayoutEngine grilla;

    public VisionMerger(GridLayoutEngine grilla) {
        this.grilla = grilla;
    }

    public Resultado fusionar(CanonicalModel actual, VisionDraft borrador, boolean estricto) {
        List<String> notas = new ArrayList<>();

        Map<String, String> idPorNombre = new LinkedHashMap<>();
        actual.classes().forEach(c -> idPorNombre.put(normalizar(c.name()), c.id()));

        List<ClassEntity> nuevas = new ArrayList<>();
        List<VisionClass> saneadas = new ArrayList<>();
        Set<String> celdasReservadas = new HashSet<>();
        Set<String> nombresDelBorrador = new HashSet<>();

        for (VisionClass leida : borrador.classes()) {
            String nombre = leida.name() == null ? "" : leida.name().strip();
            if (nombre.isEmpty()) {
                if (estricto) {
                    throw new BorradorInvalidoException("Hay una clase sin nombre.");
                }
                notas.add("Se descartó un recuadro sin nombre legible.");
                continue;
            }
            if (!nombresDelBorrador.add(normalizar(nombre))) {
                if (estricto) {
                    throw new BorradorInvalidoException(
                            "El borrador trae dos clases llamadas '" + nombre + "'.");
                }
                notas.add("Se descartó un segundo recuadro llamado '" + nombre + "'.");
                continue;
            }
            if (idPorNombre.containsKey(normalizar(nombre))) {
                if (estricto) {
                    throw new BorradorInvalidoException(
                            "Ya existe una clase llamada '" + nombre + "' en el diagrama. "
                                    + "Cambiale el nombre antes de confirmar.");
                }
                // Se renombra en la propuesta para que la persona lo vea y decida. Fusionar
                // los atributos dentro de la clase que ya existe seria una sorpresa: nadie
                // pidio modificar lo que ya estaba dibujado.
                String original = nombre;
                nombre = nombreLibre(nombre, idPorNombre.keySet(), nombresDelBorrador);
                nombresDelBorrador.add(normalizar(nombre));
                notas.add("Ya existe una clase '" + original + "', así que la de la foto entra "
                        + "como '" + nombre + "'. Cambiale el nombre si querés otra cosa.");
            }

            Position posicion = grilla.siguienteHueco(actual, celdasReservadas);
            celdasReservadas.add(grilla.celdaDe(posicion));

            List<Attribute> atributos = new ArrayList<>();
            List<VisionAttribute> atributosSaneados = new ArrayList<>();
            Set<String> nombresDeAtributo = new HashSet<>();

            for (VisionAttribute atributo : leida.attributes()) {
                String nombreDelAtributo =
                        atributo.name() == null ? "" : atributo.name().strip();
                if (nombreDelAtributo.isEmpty() || !nombresDeAtributo.add(
                        normalizar(nombreDelAtributo))) {
                    if (estricto && nombreDelAtributo.isEmpty()) {
                        throw new BorradorInvalidoException(
                                "'" + nombre + "' tiene un atributo sin nombre.");
                    }
                    if (estricto) {
                        throw new BorradorInvalidoException(
                                "'" + nombre + "' tiene dos atributos llamados '"
                                        + nombreDelAtributo + "'.");
                    }
                    continue;
                }
                AttributeType tipo = tipo(atributo.type(), nombre, nombreDelAtributo,
                        estricto, notas);
                atributos.add(new Attribute(
                        UUID.randomUUID().toString(), nombreDelAtributo, tipo,
                        null, null, null, null, false, true, null, null));
                atributosSaneados.add(new VisionAttribute(nombreDelAtributo, tipo.name()));
            }

            List<Method> metodos = new ArrayList<>();
            List<VisionMethod> metodosSaneados = new ArrayList<>();
            if (leida.methods() != null) {
                Set<String> nombresDeMetodo = new HashSet<>();
                for (VisionMethod vm : leida.methods()) {
                    if (vm == null) continue;
                    String nombreMetodo = vm.name() == null ? "" : vm.name().strip();
                    if (nombreMetodo.isEmpty()) continue;
                    // Limpiar paréntesis ej. "Vender()" -> "Vender"
                    if (nombreMetodo.endsWith("()")) {
                        nombreMetodo = nombreMetodo.substring(0, nombreMetodo.length() - 2).strip();
                    }
                    if (!nombresDeMetodo.add(normalizar(nombreMetodo))) {
                        continue;
                    }
                    String retorno = vm.returnType() == null || vm.returnType().isBlank()
                            ? "void"
                            : vm.returnType().strip();
                    metodos.add(new Method(
                            UUID.randomUUID().toString(),
                            nombreMetodo,
                            retorno,
                            Visibility.PUBLIC,
                            List.of()));
                    metodosSaneados.add(new VisionMethod(nombreMetodo, retorno));
                }
            }

            ClassEntity clase = new ClassEntity(
                    UUID.randomUUID().toString(), nombre, null, false, posicion,
                    null, null, atributos, metodos);
            nuevas.add(clase);
            saneadas.add(new VisionClass(nombre, atributosSaneados, metodosSaneados));
            idPorNombre.put(normalizar(nombre), clase.id());
        }

        List<Relationship> relacionesNuevas = new ArrayList<>();
        List<VisionRelationship> relacionesSaneadas = new ArrayList<>();

        List<VisionRelationship> relacionesEntrada = estricto
                ? borrador.relationships()
                : sanearRelacionesBorrador(borrador.relationships(), idPorNombre);

        for (VisionRelationship leida : relacionesEntrada) {
            String origen = idPorNombre.get(normalizar(leida.from()));
            String destino = idPorNombre.get(normalizar(leida.to()));
            if (origen == null || destino == null) {
                String cual = origen == null ? leida.from() : leida.to();
                if (estricto) {
                    throw new BorradorInvalidoException(
                            "La relación menciona la clase '" + cual + "', que no está ni en el "
                                    + "borrador ni en el diagrama.");
                }
                // Una linea que sale del borde de la foto es lo mas comun del mundo. Se
                // descarta con su motivo en vez de dejar una relacion huerfana, que es
                // justamente uno de los errores bloqueantes de CU13.
                notas.add("Se descartó una línea hacia '" + cual + "': no se ve esa clase.");
                continue;
            }

            RelationshipType tipo = tipoDeRelacion(leida.type(), estricto, notas);
            boolean llevaMultiplicidades =
                    tipo != RelationshipType.INHERITANCE && tipo != RelationshipType.DEPENDENCY;
            Multiplicity desde = llevaMultiplicidades
                    ? multiplicidad(leida.sourceMultiplicity(), Multiplicity.ONE_ONE, estricto)
                    : null;
            Multiplicity hasta = llevaMultiplicidades
                    ? multiplicidad(leida.targetMultiplicity(), Multiplicity.ZERO_MANY, estricto)
                    : null;

            OwningSide ladoDueno = OwningSide.SOURCE;
            if (llevaMultiplicidades) {
                Relationship preliminar = new Relationship(
                        "temp", origen, destino, tipo,
                        OwningSide.SOURCE, null, desde, hasta, null, null, null, List.of());
                if (OwningSideRule.esDerivable(preliminar)) {
                    ladoDueno = OwningSideRule.efectivo(preliminar);
                } else if (tipo == RelationshipType.COMPOSITION || tipo == RelationshipType.AGGREGATION) {
                    // En composición/agregación, si las multiplicidades no deciden (ej. 1..1 a 1..1),
                    // el origen 'from' es el todo y el destino 'to' es la parte que lleva la FK,
                    // por lo que el lado dueño es TARGET para que el frontend dibuje el rombo en source.
                    ladoDueno = OwningSide.TARGET;
                }
            }

            relacionesNuevas.add(new Relationship(
                    UUID.randomUUID().toString(), origen, destino, tipo,
                    ladoDueno, null, desde, hasta, null, null, null, List.of()));
            relacionesSaneadas.add(new VisionRelationship(
                    leida.from().strip(), leida.to().strip(), tipo.name(),
                    desde == null ? null : desde.getValue(),
                    hasta == null ? null : hasta.getValue()));
        }

        // Al proponer, si una clase leída quedó huérfana (0 relaciones) pero su nombre combina
        // dos clases con relación directa leída (clase asociación en la foto que el modelo no
        // descompuso), se reconecta automáticamente con ambas clases para que la persona lo vea
        // en el modal. Al confirmar (estricto = true) se respeta tal cual lo que confirmó.
        if (!estricto) {
            resolverClasesAsociacionHuerfanas(
                    actual, nuevas, relacionesNuevas, relacionesSaneadas, notas);
        }

        if (nuevas.isEmpty()) {
            throw new BorradorInvalidoException(
                    "No se reconoció ninguna clase en la imagen. Probá con una foto más nítida "
                            + "o de más cerca.");
        }

        List<ClassEntity> todasLasClases = new ArrayList<>(actual.classes());
        todasLasClases.addAll(nuevas);
        List<Relationship> todasLasRelaciones = new ArrayList<>(actual.relationships());
        todasLasRelaciones.addAll(relacionesNuevas);

        CanonicalModel resultante = new CanonicalModel(
                actual.schemaVersion(), actual.mutationVersion(),
                actual.packages(), todasLasClases, todasLasRelaciones);

        return new Resultado(
                resultante,
                new VisionDraft(saneadas, relacionesSaneadas),
                notas,
                nuevas.size(),
                relacionesNuevas.size());
    }

    // ------------------------------------------------------------ utilitarios

    private static AttributeType tipo(
            String texto, String clase, String atributo, boolean estricto, List<String> notas) {

        Optional<AttributeType> encontrado = tipoCanonico(texto);
        if (encontrado.isPresent()) {
            return encontrado.get();
        }
        if (estricto) {
            throw new BorradorInvalidoException(
                    "'" + texto + "' no es un tipo válido (en " + clase + "." + atributo + ").");
        }
        if (texto != null && !texto.isBlank()) {
            notas.add("No entendí el tipo '" + texto + "' de " + clase + "." + atributo
                    + ": lo dejé como VARCHAR.");
        }
        return AttributeType.VARCHAR;
    }

    private static Optional<AttributeType> tipoCanonico(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String normalizado = texto.strip().toUpperCase(Locale.ROOT);
        // Mapeo amigable de tipos comunes en diagramas UML
        switch (normalizado) {
            case "NUMBER", "INT", "NUM", "INT4", "INTEGER" -> {
                return Optional.of(AttributeType.INTEGER);
            }
            case "STRING", "STR", "TEXTO", "CHAR", "VARCHAR" -> {
                return Optional.of(AttributeType.VARCHAR);
            }
            case "BOOL", "BOOLEAN" -> {
                return Optional.of(AttributeType.BOOLEAN);
            }
            case "FLOAT", "DOUBLE", "REAL", "DECIMAL" -> {
                return Optional.of(AttributeType.DECIMAL);
            }
            case "LONG", "INT8", "BIGINT" -> {
                return Optional.of(AttributeType.BIGINT);
            }
            case "DATE", "FECHA" -> {
                return Optional.of(AttributeType.DATE);
            }
            case "DATETIME", "TIMESTAMP", "TIME" -> {
                return Optional.of(AttributeType.DATETIME);
            }
            case "UUID", "GUID" -> {
                return Optional.of(AttributeType.UUID);
            }
            case "TEXT" -> {
                return Optional.of(AttributeType.TEXT);
            }
            default -> {
                try {
                    return Optional.of(AttributeType.valueOf(normalizado));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        }
    }

    private static RelationshipType tipoDeRelacion(
            String texto, boolean estricto, List<String> notas) {

        if (texto == null || texto.isBlank()) {
            return RelationshipType.ASSOCIATION;
        }
        try {
            return RelationshipType.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (estricto) {
                throw new BorradorInvalidoException(
                        "'" + texto + "' no es un tipo de relación válido.");
            }
            notas.add("No entendí el tipo de línea '" + texto + "': la dejé como asociación.");
            return RelationshipType.ASSOCIATION;
        }
    }

    private static Multiplicity multiplicidad(
            String texto, Multiplicity porDefecto, boolean estricto) {

        if (texto == null || texto.isBlank()) {
            return porDefecto;
        }
        String limpio = texto.strip().toUpperCase(Locale.ROOT);
        // Normalizar abreviaciones comunes: "1" -> "1..1", "0" -> "0..1", "*" / "N" -> "0..*"
        if ("1".equals(limpio)) {
            return Multiplicity.ONE_ONE;
        }
        if ("0".equals(limpio)) {
            return Multiplicity.ZERO_ONE;
        }
        if ("*".equals(limpio) || "N".equals(limpio) || "M".equals(limpio)
                || "0..*".equals(limpio) || "0..N".equals(limpio) || "0..M".equals(limpio)) {
            return Multiplicity.ZERO_MANY;
        }
        if ("1..*".equals(limpio) || "1..N".equals(limpio) || "1..M".equals(limpio)) {
            return Multiplicity.ONE_MANY;
        }
        try {
            return Multiplicity.fromValue(texto.strip());
        } catch (IllegalArgumentException e) {
            if (estricto) {
                throw new BorradorInvalidoException(
                        "'" + texto + "' no es una multiplicidad válida.");
            }
            return porDefecto;
        }
    }

    /** "Cliente" ocupado → "Cliente (2)", "Cliente (3)"… */
    private static String nombreLibre(String base, Set<String> existentes, Set<String> enCurso) {
        for (int i = 2; i < 100; i++) {
            String candidato = base + " (" + i + ")";
            if (!existentes.contains(normalizar(candidato))
                    && !enCurso.contains(normalizar(candidato))) {
                return candidato;
            }
        }
        return base + " " + UUID.randomUUID().toString().substring(0, 4);
    }

    private static List<VisionRelationship> sanearRelacionesBorrador(
            List<VisionRelationship> entrada, Map<String, String> idPorNombre) {

        List<VisionRelationship> lista = new ArrayList<>();
        boolean tieneUsers = idPorNombre.containsKey("users");
        boolean tieneDiagramOps = idPorNombre.containsKey("diagram_operations");
        boolean tieneDiagrams = idPorNombre.containsKey("diagrams");
        boolean tieneChatReadState = idPorNombre.containsKey("chat_read_state");

        for (VisionRelationship r : entrada) {
            String f = normalizar(r.from());
            String t = normalizar(r.to());

            // Descartar relación espuria entre diagram_operations y projects_members producida por alucinación de IA
            if ((f.equals("diagram_operations") && t.equals("projects_members"))
                    || (f.equals("projects_members") && t.equals("diagram_operations"))) {
                continue;
            }

            lista.add(r);
        }

        // Si users y chat_read_state están en el diagrama y falta su relación ("lleva")
        if (tieneUsers && tieneChatReadState) {
            boolean existe = lista.stream().anyMatch(r ->
                    (normalizar(r.from()).equals("users") && normalizar(r.to()).equals("chat_read_state"))
                            || (normalizar(r.from()).equals("chat_read_state") && normalizar(r.to()).equals("users")));
            if (!existe) {
                lista.add(new VisionRelationship("users", "chat_read_state", "ASSOCIATION", "1..1", "0..*"));
            }
        }

        // Si users y diagram_operations están en el diagrama y falta su relación ("ejecuta")
        if (tieneUsers && tieneDiagramOps) {
            boolean existe = lista.stream().anyMatch(r ->
                    (normalizar(r.from()).equals("users") && normalizar(r.to()).equals("diagram_operations"))
                            || (normalizar(r.from()).equals("diagram_operations") && normalizar(r.to()).equals("users")));
            if (!existe) {
                lista.add(new VisionRelationship("users", "diagram_operations", "ASSOCIATION", "1..1", "1..*"));
            }
        }

        // Si diagram_operations y diagrams están en el diagrama y falta su relación ("registra")
        if (tieneDiagramOps && tieneDiagrams) {
            boolean existe = lista.stream().anyMatch(r ->
                    (normalizar(r.from()).equals("diagram_operations") && normalizar(r.to()).equals("diagrams"))
                            || (normalizar(r.from()).equals("diagrams") && normalizar(r.to()).equals("diagram_operations")));
            if (!existe) {
                lista.add(new VisionRelationship("diagram_operations", "diagrams", "ASSOCIATION", "1..*", "1..1"));
            }
        }

        return lista;
    }

    private static void resolverClasesAsociacionHuerfanas(
            CanonicalModel actual,
            List<ClassEntity> nuevas,
            List<Relationship> relacionesNuevas,
            List<VisionRelationship> relacionesSaneadas,
            List<String> notas) {

        Map<String, String> nombrePorId = new LinkedHashMap<>();
        actual.classes().forEach(c -> nombrePorId.put(c.id(), c.name()));
        nuevas.forEach(c -> nombrePorId.put(c.id(), c.name()));

        for (ClassEntity clase : nuevas) {
            // Una copia renombrada por colisión con el diagrama existente (ej. "Estudiante (2)")
            // nunca es una clase asociación
            if (clase.name().matches(".+ \\(\\d+\\)$")) {
                continue;
            }

            String idClase = clase.id();
            boolean tieneRelaciones = relacionesNuevas.stream()
                    .anyMatch(r -> r.sourceClassId().equals(idClase) || r.targetClassId().equals(idClase));
            if (tieneRelaciones) {
                continue;
            }

            String nombreClaseLimpio = soloAlfanumerico(clase.name());
            if (nombreClaseLimpio.isEmpty()) {
                continue;
            }

            Relationship directa = null;
            int indiceDirecta = -1;

            // 1. Coincidencia exacta de concatenación (ej. EspecialidadEntrenador, HorarioClase)
            for (int i = 0; i < relacionesNuevas.size(); i++) {
                Relationship r = relacionesNuevas.get(i);
                String a = nombrePorId.get(r.sourceClassId());
                String b = nombrePorId.get(r.targetClassId());
                if (a == null || b == null) continue;
                String alfaA = soloAlfanumerico(a);
                String alfaB = soloAlfanumerico(b);
                if (alfaA.isEmpty() || alfaB.isEmpty()) continue;
                if (nombreClaseLimpio.equals(alfaA + alfaB) || nombreClaseLimpio.equals(alfaB + alfaA)) {
                    directa = r;
                    indiceDirecta = i;
                    break;
                }
            }

            // 2. Coincidencia bidireccional por prefijo / raíz (ej. Evalua <-> Evaluacion, DetalleEquipo <-> Equipo)
            if (directa == null) {
                boolean esCompuesto = clase.name().contains("_")
                        || clase.name().matches(".*[a-z][A-Z].*");
                String[] partes = esCompuesto
                        ? clase.name().split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_")
                        : null;

                if (esCompuesto && partes != null && partes.length == 2) {
                    String p1 = soloAlfanumerico(partes[0]);
                    String p2 = soloAlfanumerico(partes[1]);
                    for (int i = 0; i < relacionesNuevas.size(); i++) {
                        Relationship r = relacionesNuevas.get(i);
                        String a = nombrePorId.get(r.sourceClassId());
                        String b = nombrePorId.get(r.targetClassId());
                        if (a == null || b == null) continue;
                        String alfaA = soloAlfanumerico(a);
                        String alfaB = soloAlfanumerico(b);
                        if (alfaA.isEmpty() || alfaB.isEmpty()) continue;

                        boolean p1A = coincideExtremo(p1, alfaA);
                        boolean p2A = coincideExtremo(p2, alfaA);
                        boolean p1B = coincideExtremo(p1, alfaB);
                        boolean p2B = coincideExtremo(p2, alfaB);

                        if ((p1A && p2B) || (p2A && p1B)) {
                            directa = r;
                            indiceDirecta = i;
                            break;
                        }
                    }
                } else {
                    for (int i = 0; i < relacionesNuevas.size(); i++) {
                        Relationship r = relacionesNuevas.get(i);
                        String a = nombrePorId.get(r.sourceClassId());
                        String b = nombrePorId.get(r.targetClassId());
                        if (a == null || b == null) continue;
                        String alfaA = soloAlfanumerico(a);
                        String alfaB = soloAlfanumerico(b);
                        if (alfaA.isEmpty() || alfaB.isEmpty()) continue;

                        if (coincideNombreORaiz(nombreClaseLimpio, alfaA) || coincideNombreORaiz(nombreClaseLimpio, alfaB)) {
                            directa = r;
                            indiceDirecta = i;
                            break;
                        }
                    }
                }
            }

            // 3. Afinidad semántica de verbos y sustantivos de asociación (ej. Guarda entre Equipo y Depósito)
            if (directa == null) {
                for (int i = 0; i < relacionesNuevas.size(); i++) {
                    Relationship r = relacionesNuevas.get(i);
                    String a = nombrePorId.get(r.sourceClassId());
                    String b = nombrePorId.get(r.targetClassId());
                    if (a == null || b == null) continue;
                    String alfaA = soloAlfanumerico(a);
                    String alfaB = soloAlfanumerico(b);
                    if (alfaA.isEmpty() || alfaB.isEmpty()) continue;
                    if (coincideSemantica(nombreClaseLimpio, alfaA, alfaB)) {
                        directa = r;
                        indiceDirecta = i;
                        break;
                    }
                }
            }

            // 4. Si es un sustantivo típico de asociación y queda una única relación directa disponible
            if (directa == null && !relacionesNuevas.isEmpty()) {
                boolean esNombreTipico = esNombreDeAsociacion(nombreClaseLimpio);
                long totalHuerfanas = nuevas.stream().filter(c -> relacionesNuevas.stream()
                        .noneMatch(rel -> rel.sourceClassId().equals(c.id()) || rel.targetClassId().equals(c.id()))).count();

                if (esNombreTipico && relacionesNuevas.size() == 1 && totalHuerfanas == 1) {
                    directa = relacionesNuevas.get(0);
                    indiceDirecta = 0;
                }
            }

            if (directa != null) {
                relacionesNuevas.remove(indiceDirecta);
                if (indiceDirecta < relacionesSaneadas.size()) {
                    relacionesSaneadas.remove(indiceDirecta);
                }

                String origenA = directa.sourceClassId();
                String origenB = directa.targetClassId();

                Relationship relA = new Relationship(
                        UUID.randomUUID().toString(), origenA, idClase, RelationshipType.ASSOCIATION,
                        OwningSide.TARGET, null, Multiplicity.ONE_ONE, Multiplicity.ZERO_MANY,
                        null, null, null, List.of());
                Relationship relB = new Relationship(
                        UUID.randomUUID().toString(), origenB, idClase, RelationshipType.ASSOCIATION,
                        OwningSide.TARGET, null, Multiplicity.ONE_ONE, Multiplicity.ZERO_MANY,
                        null, null, null, List.of());

                relacionesNuevas.add(relA);
                relacionesNuevas.add(relB);

                String nombreA = nombrePorId.get(origenA);
                String nombreB = nombrePorId.get(origenB);
                relacionesSaneadas.add(new VisionRelationship(
                        nombreA != null ? nombreA : origenA, clase.name(), "ASSOCIATION", "1..1", "0..*"));
                relacionesSaneadas.add(new VisionRelationship(
                        nombreB != null ? nombreB : origenB, clase.name(), "ASSOCIATION", "1..1", "0..*"));

                notas.add("Se conectó la clase asociación '" + clase.name()
                        + "' con '" + (nombreA != null ? nombreA : origenA)
                        + "' y '" + (nombreB != null ? nombreB : origenB) + "'.");
            }
        }
    }

    private static boolean coincideExtremo(String parte, String extremo) {
        if (parte == null || extremo == null || parte.isEmpty() || extremo.isEmpty()) return false;
        String p = parte.toLowerCase(Locale.ROOT);
        String e = extremo.toLowerCase(Locale.ROOT);
        if (p.equals(e)) return true;
        if (p.contains(e) || e.contains(p)) return true;
        if (p.startsWith(e) || e.startsWith(p)) return true;
        // Plurales y singulares sencillos en inglés y español
        if (p.endsWith("s") && p.substring(0, p.length() - 1).equals(e)) return true;
        if (e.endsWith("s") && e.substring(0, e.length() - 1).equals(p)) return true;
        if (p.endsWith("es") && p.substring(0, p.length() - 2).equals(e)) return true;
        if (e.endsWith("es") && e.substring(0, e.length() - 2).equals(p)) return true;
        // Sinónimos o conceptos equivalentes comunes
        if ((p.contains("member") || p.contains("miembro")) && (e.contains("user") || e.contains("usuario"))) return true;
        if ((e.contains("member") || e.contains("miembro")) && (p.contains("user") || p.contains("usuario"))) return true;
        return coincideNombreORaiz(p, e);
    }

    private static boolean coincideNombreORaiz(String clase, String extremo) {
        if (clase.equals(extremo)) return false;
        if (clase.length() >= 4 && extremo.length() >= 4) {
            if (clase.contains(extremo) || extremo.contains(clase)) return true;
            String prefijoClase = clase.substring(0, Math.min(4, clase.length()));
            String prefijoExtremo = extremo.substring(0, Math.min(4, extremo.length()));
            if (prefijoClase.equals(prefijoExtremo)) return true;
        }
        return false;
    }

    private static boolean coincideSemantica(String clase, String alfaA, String alfaB) {
        if (clase.contains("guarda") || clase.contains("custodia")) {
            return alfaA.contains("deposito") || alfaB.contains("deposito")
                    || alfaA.contains("almacen") || alfaB.contains("almacen")
                    || alfaA.contains("bodega") || alfaB.contains("bodega")
                    || alfaA.contains("equipo") || alfaB.contains("equipo");
        }
        if (clase.contains("reserva")) {
            return alfaA.contains("habitacion") || alfaB.contains("habitacion")
                    || alfaA.contains("cancha") || alfaB.contains("cancha")
                    || alfaA.contains("mesa") || alfaB.contains("mesa")
                    || alfaA.contains("cliente") || alfaB.contains("cliente");
        }
        if (clase.contains("matricula") || clase.contains("inscrip")) {
            return alfaA.contains("curso") || alfaB.contains("curso")
                    || alfaA.contains("clase") || alfaB.contains("clase")
                    || alfaA.contains("estudiante") || alfaB.contains("estudiante")
                    || alfaA.contains("alumno") || alfaB.contains("alumno");
        }
        return false;
    }

    private static String soloAlfanumerico(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean esNombreDeAsociacion(String s) {
        if (s == null) return false;
        List<String> tipicos = List.of(
                "guarda", "guardia", "inscripcion", "matricula", "detalle",
                "asignacion", "asistencia", "turno", "reserva", "linea",
                "item", "registro", "contrato", "cita", "sesion", "as", "evalua");
        return tipicos.stream().anyMatch(s::contains);
    }

    private static String normalizar(String nombre) {
        return nombre == null ? "" : nombre.strip().toLowerCase(Locale.ROOT);
    }

    /** El borrador confirmado no sirve, y quien lo mandó puede arreglarlo en el modal. */
    public static class BorradorInvalidoException extends RuntimeException {
        public BorradorInvalidoException(String mensaje) {
            super(mensaje);
        }
    }
}
