package com.modelcollab.vision.service;

import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.vision.dto.VisionAttribute;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionDraft;
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

            ClassEntity clase = new ClassEntity(
                    UUID.randomUUID().toString(), nombre, null, false, posicion,
                    null, null, atributos, List.of());
            nuevas.add(clase);
            saneadas.add(new VisionClass(nombre, atributosSaneados));
            idPorNombre.put(normalizar(nombre), clase.id());
        }

        List<Relationship> relacionesNuevas = new ArrayList<>();
        List<VisionRelationship> relacionesSaneadas = new ArrayList<>();

        for (VisionRelationship leida : borrador.relationships()) {
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

            relacionesNuevas.add(new Relationship(
                    UUID.randomUUID().toString(), origen, destino, tipo,
                    null, null, desde, hasta, null, null, null, List.of()));
            relacionesSaneadas.add(new VisionRelationship(
                    leida.from().strip(), leida.to().strip(), tipo.name(),
                    desde == null ? null : desde.getValue(),
                    hasta == null ? null : hasta.getValue()));
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
        try {
            return Optional.of(AttributeType.valueOf(texto.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
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
