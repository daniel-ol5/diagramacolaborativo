package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.service.CanonicalMutationApplier;
import com.modelcollab.collaboration.service.MutacionRechazadaException;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.Visibility;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Semántica de las operaciones de §8.1 sobre el modelo canónico.
 *
 * <p>Se prueba contra el fixture real de la barbería y no contra un modelo de juguete: es el
 * mismo diagrama que se demuestra ante el tribunal, y así una operación que lo rompa se ve acá y
 * no en la demo.
 */
class CanonicalMutationApplierTest {

    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            JacksonConfig.canonicalObjectMapper();
    private final CanonicalMutationApplier applier = new CanonicalMutationApplier(mapper);

    private CanonicalModel barberia() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("reference-diagrams/barberia.json")) {
            return mapper.readValue(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), CanonicalModel.class);
        }
    }

    private String idDe(CanonicalModel m, String nombre) {
        return m.classes().stream()
                .filter(c -> c.name().equals(nombre))
                .findFirst().orElseThrow().id();
    }

    // ------------------------------------------------------------------ clases

    @Test
    @DisplayName("ADD_CLASS agrega la clase aplicando los defaults de §7")
    void agregaClase() throws Exception {
        CanonicalModel antes = barberia();

        CanonicalModel despues = applier.apply(antes, OperationType.ADD_CLASS, null, Map.of(
                "id", "clase-nueva",
                "name", "Factura",
                "position", Map.of("x", 10.0, "y", 20.0)));

        assertThat(despues.classes()).hasSize(antes.classes().size() + 1);
        var nueva = despues.classes().stream()
                .filter(c -> c.id().equals("clase-nueva")).findFirst().orElseThrow();
        // Los defaults se aplican igual que si hubiera entrado por REST: mismo mapper canónico.
        assertThat(nueva.width()).isEqualTo(240d);
        assertThat(nueva.height()).isEqualTo(180d);
        assertThat(nueva.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(nueva.isAbstract()).isFalse();
        assertThat(nueva.attributes()).isEmpty();
    }

    @Test
    @DisplayName("ADD_CLASS con un id que ya existe se rechaza en vez de duplicar")
    void rechazaIdDuplicado() throws Exception {
        CanonicalModel antes = barberia();
        String existente = antes.classes().get(0).id();

        assertThatThrownBy(() -> applier.apply(antes, OperationType.ADD_CLASS, null,
                Map.of("id", existente, "name", "Repetida")))
                .isInstanceOf(MutacionRechazadaException.class)
                .extracting(e -> ((MutacionRechazadaException) e).getCode())
                .isEqualTo(MutacionRechazadaException.ID_DUPLICADO);
    }

    @Test
    @DisplayName("MOVE_CLASS cambia la posición y no toca nada más")
    void mueveClase() throws Exception {
        CanonicalModel antes = barberia();
        String cita = idDe(antes, "Cita");
        var original = antes.classes().stream()
                .filter(c -> c.id().equals(cita)).findFirst().orElseThrow();

        CanonicalModel despues = applier.apply(
                antes, OperationType.MOVE_CLASS, cita, Map.of("x", 999.0, "y", 888.0));

        var movida = despues.classes().stream()
                .filter(c -> c.id().equals(cita)).findFirst().orElseThrow();
        assertThat(movida.position().x()).isEqualTo(999.0);
        assertThat(movida.position().y()).isEqualTo(888.0);
        // El resto de la clase sobrevive intacto: mover no puede perder atributos.
        assertThat(movida.name()).isEqualTo(original.name());
        assertThat(movida.attributes()).isEqualTo(original.attributes());
        assertThat(movida.methods()).isEqualTo(original.methods());
        assertThat(despues.relationships()).isEqualTo(antes.relationships());
    }

    @Test
    @DisplayName("RENAME_CLASS conserva id, atributos y métodos")
    void renombraClase() throws Exception {
        CanonicalModel antes = barberia();
        String cita = idDe(antes, "Cita");

        CanonicalModel despues = applier.apply(
                antes, OperationType.RENAME_CLASS, cita, Map.of("name", "Turno"));

        var renombrada = despues.classes().stream()
                .filter(c -> c.id().equals(cita)).findFirst().orElseThrow();
        assertThat(renombrada.name()).isEqualTo("Turno");
        assertThat(renombrada.attributes()).isNotEmpty();
    }

    @Test
    @DisplayName("DELETE_CLASS limpia en cascada las relaciones huérfanas")
    void borrarClaseLimpiaRelaciones() throws Exception {
        CanonicalModel antes = barberia();
        String cita = idDe(antes, "Cita");
        long relacionesDeCita = antes.relationships().stream()
                .filter(r -> cita.equals(r.sourceClassId()) || cita.equals(r.targetClassId()))
                .count();
        assertThat(relacionesDeCita).isGreaterThan(0);

        CanonicalModel despues = applier.apply(antes, OperationType.DELETE_CLASS, cita, Map.of());

        assertThat(despues.classes()).noneMatch(c -> c.id().equals(cita));
        // Sin esta limpieza el diagrama quedaría igual a invalid-cases/relacion-huerfana.json,
        // que el validador del Día 10 tiene que rechazar. El servidor no puede fabricar sus
        // propios casos inválidos.
        assertThat(despues.relationships())
                .noneMatch(r -> cita.equals(r.sourceClassId()) || cita.equals(r.targetClassId()));
        assertThat(despues.relationships())
                .hasSize(antes.relationships().size() - (int) relacionesDeCita);
        // Y desaparece de la lista de miembros de su paquete.
        assertThat(despues.packages()).allSatisfy(p -> assertThat(p.classIds()).doesNotContain(cita));
    }

    @Test
    @DisplayName("Una operación sobre un elemento inexistente da TARGET_NOT_FOUND")
    void objetivoInexistente() throws Exception {
        CanonicalModel antes = barberia();

        assertThatThrownBy(() -> applier.apply(
                antes, OperationType.MOVE_CLASS, "no-existe", Map.of("x", 1.0, "y", 2.0)))
                .isInstanceOf(MutacionRechazadaException.class)
                .extracting(e -> ((MutacionRechazadaException) e).getCode())
                .isEqualTo(MutacionRechazadaException.OBJETIVO_INEXISTENTE);
    }

    // --------------------------------------------------------------- atributos

    @Test
    @DisplayName("ADD_ATTRIBUTE inserta en la clase indicada y aplica los defaults de §7")
    void agregaAtributo() throws Exception {
        CanonicalModel antes = barberia();
        String cliente = idDe(antes, "Cliente");
        int cuantos = antes.classes().stream()
                .filter(c -> c.id().equals(cliente)).findFirst().orElseThrow().attributes().size();

        CanonicalModel despues = applier.apply(antes, OperationType.ADD_ATTRIBUTE, cliente, Map.of(
                "id", "attr-nuevo", "name", "apodo", "type", "VARCHAR"));

        var modificada = despues.classes().stream()
                .filter(c -> c.id().equals(cliente)).findFirst().orElseThrow();
        assertThat(modificada.attributes()).hasSize(cuantos + 1);
        var nuevo = modificada.attributes().get(cuantos);
        assertThat(nuevo.length()).isEqualTo(255);
        assertThat(nuevo.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(nuevo.isNullable()).isTrue();
    }

    @Test
    @DisplayName("UPDATE_ATTRIBUTE encuentra la clase dueña sola, por el id del atributo")
    void actualizaAtributo() throws Exception {
        CanonicalModel antes = barberia();
        var cliente = antes.classes().stream()
                .filter(c -> c.name().equals("Cliente")).findFirst().orElseThrow();
        var atributo = cliente.attributes().get(1);

        CanonicalModel despues = applier.apply(
                antes, OperationType.UPDATE_ATTRIBUTE, atributo.id(), Map.of(
                        "id", atributo.id(), "name", "nombreCompleto",
                        "type", "VARCHAR", "length", 120));

        var modificado = despues.classes().stream()
                .filter(c -> c.id().equals(cliente.id())).findFirst().orElseThrow()
                .attributes().get(1);
        assertThat(modificado.name()).isEqualTo("nombreCompleto");
        assertThat(modificado.length()).isEqualTo(120);
    }

    @Test
    @DisplayName("Un payload cuyo id no coincide con el targetId se rechaza")
    void idDelPayloadDebeCoincidir() throws Exception {
        CanonicalModel antes = barberia();
        var atributo = antes.classes().get(0).attributes().get(0);

        assertThatThrownBy(() -> applier.apply(
                antes, OperationType.UPDATE_ATTRIBUTE, atributo.id(),
                Map.of("id", "otro-id", "name", "x", "type", "TEXT")))
                .isInstanceOf(MutacionRechazadaException.class)
                .hasMessageContaining("no coincide");
    }

    @Test
    @DisplayName("DELETE_ATTRIBUTE quita sólo ese atributo")
    void borraAtributo() throws Exception {
        CanonicalModel antes = barberia();
        var cliente = antes.classes().stream()
                .filter(c -> c.name().equals("Cliente")).findFirst().orElseThrow();
        var atributo = cliente.attributes().get(1);

        CanonicalModel despues = applier.apply(
                antes, OperationType.DELETE_ATTRIBUTE, atributo.id(), Map.of());

        var modificada = despues.classes().stream()
                .filter(c -> c.id().equals(cliente.id())).findFirst().orElseThrow();
        assertThat(modificada.attributes()).hasSize(cliente.attributes().size() - 1);
        assertThat(modificada.attributes()).noneMatch(a -> a.id().equals(atributo.id()));
    }

    // ------------------------------------------------------------- relaciones

    @Test
    @DisplayName("DELETE_RELATIONSHIP borra el conector y deja las clases en su lugar")
    void borraRelacion() throws Exception {
        CanonicalModel antes = barberia();
        String relId = antes.relationships().get(0).id();

        CanonicalModel despues = applier.apply(
                antes, OperationType.DELETE_RELATIONSHIP, relId, Map.of());

        assertThat(despues.relationships()).hasSize(antes.relationships().size() - 1);
        assertThat(despues.classes()).hasSameSizeAs(antes.classes());
    }

    @Test
    @DisplayName("UPDATE_WAYPOINTS cambia los puntos sin recrear la relación")
    void actualizaWaypoints() throws Exception {
        CanonicalModel antes = barberia();
        var relacion = antes.relationships().get(0);

        CanonicalModel despues = applier.apply(
                antes, OperationType.UPDATE_WAYPOINTS, relacion.id(),
                Map.of("waypoints", List.of(Map.of("x", 1.0, "y", 2.0), Map.of("x", 3.0, "y", 4.0))));

        var modificada = despues.relationships().stream()
                .filter(r -> r.id().equals(relacion.id())).findFirst().orElseThrow();
        assertThat(modificada.waypoints()).hasSize(2);
        // La identidad y la semántica de la relación no se tocan: eso es lo que distingue
        // UPDATE_WAYPOINTS de borrar y recrear.
        assertThat(modificada.type()).isEqualTo(relacion.type());
        assertThat(modificada.owningSide()).isEqualTo(relacion.owningSide());
        assertThat(modificada.sourceMultiplicity()).isEqualTo(relacion.sourceMultiplicity());
    }

    // --------------------------------------------------------------- paquetes

    @Test
    @DisplayName("DELETE_PACKAGE conserva las clases que contenía (§8.1)")
    void borrarPaqueteConservaLasClases() throws Exception {
        CanonicalModel antes = barberia();
        var paquete = antes.packages().get(0);
        assertThat(paquete.classIds()).isNotEmpty();

        CanonicalModel despues = applier.apply(
                antes, OperationType.DELETE_PACKAGE, paquete.id(), Map.of());

        assertThat(despues.packages()).noneMatch(p -> p.id().equals(paquete.id()));
        // El paquete es una agrupación lógica; borrarlo no puede llevarse el modelo por delante.
        assertThat(despues.classes()).hasSameSizeAs(antes.classes());
    }

    // ------------------------------------------------------------- contrato

    @ParameterizedTest(name = "{0} se rechaza con OP_NOT_IMPLEMENTED")
    @EnumSource(value = OperationType.class,
            names = {"UPDATE_RELATIONSHIP", "USER_CURSOR"})
    @DisplayName("Lo que el plan agenda para más adelante se rechaza explícito, no en silencio")
    void loNoImplementadoAvisa(OperationType op) throws Exception {
        assertThatThrownBy(() -> applier.apply(barberia(), op, "algo", Map.of()))
                .isInstanceOf(MutacionRechazadaException.class)
                .extracting(e -> ((MutacionRechazadaException) e).getCode())
                .isEqualTo(MutacionRechazadaException.OP_NO_IMPLEMENTADA);
    }

    @Test
    @DisplayName("BULK_MERGE reemplaza el diagrama entero con el sub-grafo recibido")
    void bulkMergeReemplazaElDiagrama() throws Exception {
        CanonicalModel antes = barberia();
        CanonicalModel entrante = mapper.readValue("""
                {"schemaVersion":"1.0.0","mutationVersion":1,"packages":[],"relationships":[],
                 "classes":[{"id":"nueva","name":"Importada","position":{"x":1.0,"y":2.0},
                   "attributes":[{"id":"a","name":"id","type":"UUID","isPrimaryKey":true}]}]}
                """, CanonicalModel.class);

        CanonicalModel despues = applier.apply(antes, OperationType.BULK_MERGE, null,
                mapper.convertValue(entrante, new com.fasterxml.jackson.core.type.TypeReference<
                        java.util.Map<String, Object>>() {}));

        // Es un REEMPLAZO y no una fusion elemento por elemento: importar trae un modelo
        // completo con sus propios ids, y mezclarlo dejaria dos jerarquias que nadie dibujo.
        assertThat(despues.classes()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("Importada"));
        assertThat(despues.relationships()).isEmpty();
        // La version la asigna el servicio, no el payload.
        assertThat(despues.mutationVersion()).isEqualTo(antes.mutationVersion());
    }

    @Test
    @DisplayName("Un BULK_MERGE vacio se rechaza: dejaria el diagrama sin nada")
    void bulkMergeVacio() throws Exception {
        assertThatThrownBy(() -> applier.apply(barberia(), OperationType.BULK_MERGE, null,
                java.util.Map.of("schemaVersion", "1.0.0", "mutationVersion", 1,
                        "classes", java.util.List.of())))
                .isInstanceOf(MutacionRechazadaException.class);
    }

    @Test
    @DisplayName("Un payload con un campo que §7 no define falla en el acto")
    void payloadConCampoDesconocido() throws Exception {
        // El mapper canónico va con FAIL_ON_UNKNOWN_PROPERTIES: es el candado que detecta que
        // alguien se desvió del schema en el momento en que ocurre.
        assertThatThrownBy(() -> applier.apply(barberia(), OperationType.ADD_CLASS, null,
                Map.of("id", "x", "name", "Y", "campoInventado", "z")))
                .isInstanceOf(MutacionRechazadaException.class)
                .extracting(e -> ((MutacionRechazadaException) e).getCode())
                .isEqualTo(MutacionRechazadaException.PAYLOAD_INVALIDO);
    }

    @Test
    @DisplayName("El modelo de entrada nunca se modifica: las mutaciones devuelven uno nuevo")
    void elModeloDeEntradaEsInmutable() throws Exception {
        CanonicalModel antes = barberia();
        int clasesAntes = antes.classes().size();

        applier.apply(antes, OperationType.ADD_CLASS, null, Map.of("id", "x", "name", "Nueva"));

        // Con objetos mutables, dos hilos atendiendo a dos colaboradores corromperían el mismo
        // diagrama. Con records eso es imposible por construcción (§2.3).
        assertThat(antes.classes()).hasSize(clasesAntes);
    }
}
