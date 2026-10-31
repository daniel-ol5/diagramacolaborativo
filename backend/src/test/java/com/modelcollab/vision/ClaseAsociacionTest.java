package com.modelcollab.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.vision.dto.VisionAttribute;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionRelationship;
import com.modelcollab.vision.service.VisionMerger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Una <b>clase asociación</b> de la pizarra entra al diagrama como una clase normal con dos
 * relaciones.
 *
 * <h2>Qué es y por qué importa</h2>
 *
 * <p>UML permite colgar una caja de una línea —con un trazo punteado— para guardar los datos
 * <b>del vínculo</b>: {@code Estudiante} y {@code Curso} en muchos-a-muchos, y la inscripción
 * tiene <i>fecha</i> y <i>nota</i>, que no son de ninguna de las dos. <b>Una pizarra de verdad
 * tiene clases asociación</b>, y CU17 existe justamente para leer pizarras de verdad.
 *
 * <h2>No hace falta tocar el modelo canónico, y este test es la prueba</h2>
 *
 * <p>El canónico no tiene esa construcción. Pero <b>no necesita tenerla</b>: una clase asociación
 * <i>se resuelve</i> en una clase normal con dos relaciones, que es lo que hace cualquier
 * herramienta al bajar a base de datos — una tabla intermedia con columnas propias es una tabla
 * como cualquier otra.
 *
 * <p>Así que el sistema no necesita <b>representarla</b>: necesita <b>resolverla bien al
 * leerla</b>. Eso vive en el prompt de visión, no en el modelo.
 *
 * <h2>Lo que este archivo prueba, y lo que NO</h2>
 *
 * <p><b>Prueba el destino</b>, sin red: que un borrador con la forma resuelta sobreviva la fusión
 * y entre al canónico con sus dos relaciones. Si esto fallara, cambiar el prompt no serviría de
 * nada — se estaría pidiendo algo que después se pierde.
 *
 * <p><b>No prueba el origen</b>: que el modelo lea una caja punteada y la devuelva así. Eso sólo
 * se puede comprobar con una foto real y el modelo real, y el día que se escribió la regla la
 * cuota estaba agotada. Queda marcado como no verificado en {@code GeminiVisionModelClient}.
 */
class ClaseAsociacionTest {

    private final VisionMerger fusionador =
            new VisionMerger(new GridLayoutEngine());

    /** El caso de libro: Estudiante y Curso, con Inscripcion colgando de la línea. */
    private static VisionDraft borradorResuelto() {
        return new VisionDraft(
                List.of(
                        new VisionClass("Estudiante", List.of(
                                new VisionAttribute("legajo", "VARCHAR"))),
                        new VisionClass("Curso", List.of(
                                new VisionAttribute("nombre", "VARCHAR"))),
                        // La caja que colgaba de la línea, ya resuelta en clase normal.
                        new VisionClass("Inscripcion", List.of(
                                new VisionAttribute("fecha", "DATE"),
                                new VisionAttribute("nota", "DECIMAL")))),
                List.of(
                        new VisionRelationship("Estudiante", "Inscripcion",
                                "ASSOCIATION", "1..1", "0..*"),
                        new VisionRelationship("Curso", "Inscripcion",
                                "ASSOCIATION", "1..1", "0..*")));
    }

    @Test
    @DisplayName("La clase del vínculo entra con sus atributos y con SUS DOS relaciones")
    void laClaseDelVinculoEntraEntera() {
        VisionMerger.Resultado r = fusionador.fusionar(
                CanonicalModel.empty(), borradorResuelto(), false);

        assertThat(r.clasesNuevas()).isEqualTo(3);
        assertThat(r.relacionesNuevas())
                .as("sin las DOS relaciones, la clase entra suelta y se pierde a qué "
                        + "estaba enganchada — que es justo lo que hay que evitar")
                .isEqualTo(2);

        ClassEntity inscripcion = r.modelo().classes().stream()
                .filter(c -> c.name().equals("Inscripcion"))
                .findFirst()
                .orElseThrow();

        // Los datos del vínculo son los que no son de ninguna de las dos clases. Si se perdieran,
        // la clase asociación no habría servido de nada.
        assertThat(inscripcion.attributes())
                .extracting(a -> a.name())
                .containsExactlyInAnyOrder("fecha", "nota");

        String id = inscripcion.id();
        List<Relationship> haciaElVinculo = r.modelo().relationships().stream()
                .filter(x -> x.targetClassId().equals(id))
                .toList();
        assertThat(haciaElVinculo)
                .as("las dos clases que unía la línea tienen que apuntar a la del vínculo")
                .hasSize(2);
    }

    /**
     * Sobre un diagrama que YA tiene una de las clases, la foto trae una COPIA. Es deliberado, y
     * conviene tenerlo escrito porque no es lo que uno esperaría.
     *
     * <p>Yo escribí este test esperando que {@code Estudiante} se reusara, y el resultado fue
     * {@code Estudiante (2)}. No es un defecto: <b>la foto es una propuesta, no una autoridad</b>.
     * Fusionar en silencio contra una clase existente significaría que una pizarra mal leída
     * puede pisar atributos que alguien modeló a mano, y eso sí sería grave.
     *
     * <p>La contrapartida es real y hay que decirla: <b>fotografiar una pizarra que repite clases
     * que ya están produce duplicados</b>. Lo salva el modal de revisión, que muestra el aviso
     * antes de confirmar — pero la persona tiene que mirarlo.
     */
    @Test
    @DisplayName("Si una clase ya existía, la foto trae una COPIA y lo avisa")
    void loQueYaExistiaEntraComoCopiaYSeAvisa() {
        VisionMerger.Resultado primero = fusionador.fusionar(
                CanonicalModel.empty(),
                new VisionDraft(List.of(new VisionClass("Estudiante", List.of())), List.of()),
                false);

        VisionMerger.Resultado segundo =
                fusionador.fusionar(primero.modelo(), borradorResuelto(), false);

        assertThat(segundo.modelo().classes())
                .extracting(ClassEntity::name)
                .containsExactlyInAnyOrder("Estudiante", "Estudiante (2)", "Curso", "Inscripcion");

        // Lo que SÍ tiene que pasar pase lo que pase: las dos relaciones del vínculo entran, y
        // enganchan a la copia — no quedan colgando de nada.
        assertThat(segundo.relacionesNuevas()).isEqualTo(2);
        assertThat(segundo.notas())
                .as("la persona tiene que enterarse de que se renombró antes de confirmar")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Si el modelo de visión leyó la clase asociación huérfana pero con relación directa entre extremos, se reconecta")
    void claseAsociacionHuerfanaSeReconecta() {
        // Simula la foto donde Gemini vio Entrenador y Especialidad conectados directamente,
        // y vio la caja EspecialidadEntrenador suelta (0 relaciones).
        VisionDraft draftConHuerfana = new VisionDraft(
                List.of(
                        new VisionClass("Entrenador", List.of(new VisionAttribute("nombre", "VARCHAR"))),
                        new VisionClass("Especialidad", List.of(new VisionAttribute("titulo", "VARCHAR"))),
                        new VisionClass("EspecialidadEntrenador", List.of(
                                new VisionAttribute("fecha", "DATE"),
                                new VisionAttribute("vigente", "BOOLEAN")))),
                List.of(
                        new VisionRelationship("Entrenador", "Especialidad", "ASSOCIATION", "1..1", "0..*")));

        VisionMerger.Resultado r = fusionador.fusionar(CanonicalModel.empty(), draftConHuerfana, false);

        assertThat(r.clasesNuevas()).isEqualTo(3);
        // La relación directa se reemplaza por dos relaciones hacia EspecialidadEntrenador
        assertThat(r.relacionesNuevas()).isEqualTo(2);

        ClassEntity intermedia = r.modelo().classes().stream()
                .filter(c -> c.name().equals("EspecialidadEntrenador"))
                .findFirst()
                .orElseThrow();

        List<Relationship> haciaIntermedia = r.modelo().relationships().stream()
                .filter(rel -> rel.targetClassId().equals(intermedia.id()))
                .toList();

        assertThat(haciaIntermedia).hasSize(2);
        assertThat(r.notas()).anyMatch(n -> n.contains("EspecialidadEntrenador"));
    }

    @Test
    @DisplayName("Una clase asociación con nombre propio como 'Guarda' se reconecta entre las clases de la relación")
    void claseAsociacionGuardaSeReconecta() {
        // En la pizarra se dibuja Socio y Equipo con Guarda colgando de la línea punteada
        VisionDraft draft = new VisionDraft(
                List.of(
                        new VisionClass("Socio", List.of(new VisionAttribute("ci", "VARCHAR"))),
                        new VisionClass("Equipo", List.of(new VisionAttribute("codigo", "VARCHAR"))),
                        new VisionClass("Guarda", List.of(new VisionAttribute("fecha", "DATE")))),
                List.of(
                        new VisionRelationship("Socio", "Equipo", "ASSOCIATION", "1..1", "0..*")));

        VisionMerger.Resultado r = fusionador.fusionar(CanonicalModel.empty(), draft, false);

        assertThat(r.clasesNuevas()).isEqualTo(3);
        assertThat(r.relacionesNuevas()).isEqualTo(2);

        ClassEntity guarda = r.modelo().classes().stream()
                .filter(c -> c.name().equals("Guarda"))
                .findFirst()
                .orElseThrow();

        List<Relationship> haciaGuarda = r.modelo().relationships().stream()
                .filter(rel -> rel.targetClassId().equals(guarda.id()))
                .toList();

        assertThat(haciaGuarda).hasSize(2);
        assertThat(r.notas()).anyMatch(n -> n.contains("Guarda"));
    }

    @Test
    @DisplayName("En un diagrama con múltiples clases asociación (compuestas, verbales y por raíz), todas se reconectan")
    void diagramaCompletoConMultiplesClasesAsociacionSeReconecta() {
        VisionDraft draft = new VisionDraft(
                List.of(
                        new VisionClass("Persona", List.of(new VisionAttribute("ci", "VARCHAR"))),
                        new VisionClass("Cliente", List.of(new VisionAttribute("edad", "INTEGER"))),
                        new VisionClass("Evaluacion", List.of(new VisionAttribute("id", "VARCHAR"))),
                        new VisionClass("Evalua", List.of(new VisionAttribute("resultado", "VARCHAR"))),
                        new VisionClass("Entrenador", List.of(new VisionAttribute("nombre", "VARCHAR"))),
                        new VisionClass("Especialidad", List.of(new VisionAttribute("titulo", "VARCHAR"))),
                        new VisionClass("EspecialidadEntrenador", List.of()),
                        new VisionClass("Clase", List.of(new VisionAttribute("id", "VARCHAR"))),
                        new VisionClass("Horario", List.of(new VisionAttribute("dia", "VARCHAR"))),
                        new VisionClass("HorarioClase", List.of()),
                        new VisionClass("Equipo", List.of(new VisionAttribute("id", "VARCHAR"))),
                        new VisionClass("Deposito", List.of(new VisionAttribute("id", "VARCHAR"))),
                        new VisionClass("Guarda", List.of())),
                List.of(
                        new VisionRelationship("Cliente", "Persona", "INHERITANCE", null, null),
                        new VisionRelationship("Cliente", "Evaluacion", "ASSOCIATION", "1..1", "0..*"),
                        new VisionRelationship("Entrenador", "Especialidad", "ASSOCIATION", "1..1", "0..*"),
                        new VisionRelationship("Clase", "Horario", "ASSOCIATION", "1..1", "0..*"),
                        new VisionRelationship("Equipo", "Deposito", "ASSOCIATION", "1..1", "0..*")));

        VisionMerger.Resultado r = fusionador.fusionar(CanonicalModel.empty(), draft, false);

        assertThat(r.clasesNuevas()).isEqualTo(13);
        // De 5 relaciones originales: 1 herencia queda intacta, 4 asociaciones directas se convierten en 8 hacia las 4 clases asociación
        // Total = 1 + 8 = 9 relaciones
        assertThat(r.relacionesNuevas()).isEqualTo(9);

        // Verificar que ninguna de las 4 clases asociación quedó huérfana
        List<String> asociacionNames = List.of("Evalua", "EspecialidadEntrenador", "HorarioClase", "Guarda");
        for (String nombre : asociacionNames) {
            ClassEntity entidad = r.modelo().classes().stream()
                    .filter(c -> c.name().equals(nombre))
                    .findFirst()
                    .orElseThrow();
            List<Relationship> conexiones = r.modelo().relationships().stream()
                    .filter(rel -> rel.targetClassId().equals(entidad.id()) || rel.sourceClassId().equals(entidad.id()))
                    .toList();
            assertThat(conexiones)
                    .as("la clase asociación " + nombre + " debe tener 2 conexiones")
                    .hasSize(2);
        }
    }
}

