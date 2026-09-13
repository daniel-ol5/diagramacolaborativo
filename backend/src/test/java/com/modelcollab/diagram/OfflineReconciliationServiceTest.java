package com.modelcollab.diagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.collaboration.service.CanonicalMutationApplier;
import com.modelcollab.collaboration.service.SequenceService;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.diagram.dto.OfflineOperation;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.ReconciliationReport;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.model.DiagramOperation;
import com.modelcollab.diagram.repository.DiagramOperationRepository;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.OfflineReconciliationService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.project.model.Project;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Algoritmo de rebase de §9.2, probado sin base de datos: los repositorios van mockeados y el
 * aplicador es el real, porque la semántica de las operaciones es justamente lo que se prueba.
 *
 * <p>El diagrama de partida es el fixture de la barbería — el mismo que se demuestra ante el
 * tribunal.
 */
@ExtendWith(MockitoExtension.class)
class OfflineReconciliationServiceTest {

    @Mock private DiagramRepository diagramRepository;
    @Mock private DiagramOperationRepository operationRepository;
    @Mock private UserRepository userRepository;
    @Mock private SequenceService sequenceService;

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private OfflineReconciliationService servicio;

    private final UUID diagramId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Diagram diagrama;
    private CanonicalModel inicial;

    @BeforeEach
    void prepararServicio() throws Exception {
        servicio = new OfflineReconciliationService(
                diagramRepository, operationRepository, userRepository,
                new CanonicalMutationApplier(mapper), sequenceService, mapper);

        String json;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("reference-diagrams/barberia.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        inicial = mapper.readValue(json, CanonicalModel.class);

        diagrama = new Diagram(new Project("Barberia", null, null), "Diagrama");
        ReflectionTestUtils.setField(diagrama, "id", diagramId);
        diagrama.setCurrentState(json);

        lenient().when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(diagrama));
        lenient().when(operationRepository.findByDiagramIdAndClientMutationId(eq(diagramId), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(operationRepository.findByDiagramIdOrderBySequenceNumAsc(diagramId))
                .thenReturn(List.of());
        lenient().when(sequenceService.reservar(eq(diagramId), anyInt()))
                .thenAnswer(inv -> 100L + (int) inv.getArgument(1) - 1);
    }

    private String idDe(String nombreDeClase) {
        return inicial.classes().stream()
                .filter(c -> c.name().equals(nombreDeClase))
                .findFirst().orElseThrow().id();
    }

    private ReconciliationReport reconciliar(OfflineOperation... operaciones) {
        return servicio.reconciliar(
                diagramId, userId, new OfflineSyncRequest(1L, List.of(operaciones)));
    }

    private static OfflineOperation op(
            String tipo, String targetId, String mutId, Map<String, Object> payload) {
        return new OfflineOperation(tipo, targetId, mutId, payload, System.currentTimeMillis());
    }

    // ------------------------------------------------------------- camino feliz

    @Test
    @DisplayName("Una cola sin conflictos se aplica entera y sube la versión")
    void colaLimpia() {
        String cita = idDe("Cita");

        ReconciliationReport reporte = reconciliar(
                op("MOVE_CLASS", cita, "m1", Map.of("x", 10.0, "y", 20.0)),
                op("MOVE_CLASS", cita, "m2", Map.of("x", 30.0, "y", 40.0)),
                op("ADD_CLASS", null, "m3", Map.of("id", "nueva-1", "name", "Factura")));

        assertThat(reporte.appliedCount()).isEqualTo(3);
        assertThat(reporte.discardedCount()).isZero();
        assertThat(reporte.discardedDetails()).isEmpty();

        // MOVE_CLASS es idempotente y gana el último (§9.2): la clase queda en 30/40, no en 10/20.
        var movida = reporte.snapshot().classes().stream()
                .filter(c -> c.id().equals(cita)).findFirst().orElseThrow();
        assertThat(movida.position().x()).isEqualTo(30.0);
        assertThat(movida.position().y()).isEqualTo(40.0);

        assertThat(reporte.snapshot().classes()).anyMatch(c -> c.id().equals("nueva-1"));
        // Un solo bloque de secuencias reservado para todo el lote, no uno por operación.
        verify(sequenceService, times(1)).reservar(diagramId, 3);
        verify(operationRepository, times(3)).save(any());
    }

    @Test
    @DisplayName("La bitácora guarda cada operación con su secuencia consecutiva")
    void bitacoraConsecutiva() {
        String cita = idDe("Cita");
        reconciliar(
                op("MOVE_CLASS", cita, "m1", Map.of("x", 1.0, "y", 1.0)),
                op("MOVE_CLASS", cita, "m2", Map.of("x", 2.0, "y", 2.0)));

        var capturadas = org.mockito.ArgumentCaptor.forClass(DiagramOperation.class);
        verify(operationRepository, times(2)).save(capturadas.capture());
        assertThat(capturadas.getAllValues()).extracting(DiagramOperation::getSequenceNum)
                .containsExactly(100L, 101L);
        assertThat(capturadas.getAllValues()).extracting(DiagramOperation::getTargetId)
                .containsOnly(cita);
    }

    // ---------------------------------------------------- políticas de descarte

    @Test
    @DisplayName("§9.2: ADD_ATTRIBUTE se descarta si otro borró la clase, nombrándola")
    void atributoSobreClaseBorrada() {
        String cliente = idDe("Cliente");
        // Otro colaborador borró Cliente mientras este usuario estaba sin conexión. En la
        // bitácora queda su DELETE_CLASS, y es la única fuente que conserva el nombre: en el
        // estado actual la clase ya no está.
        diagrama.setCurrentState(sinLaClase(cliente));
        when(operationRepository.findByDiagramIdOrderBySequenceNumAsc(diagramId))
                .thenReturn(List.of(new DiagramOperation(
                        diagrama, null, "DELETE_CLASS", cliente,
                        "{\"name\":\"Cliente\"}", "beto-1", 5L)));

        ReconciliationReport reporte = reconciliar(
                op("ADD_ATTRIBUTE", cliente, "m1",
                        Map.of("id", "attr-1", "name", "telefono", "type", "VARCHAR")));

        assertThat(reporte.appliedCount()).isZero();
        assertThat(reporte.discardedCount()).isEqualTo(1);
        // El mensaje del ejemplo de §9.2: nombra el atributo, no su UUID.
        // El mensaje exacto que pide §9.2: nombra el atributo Y la clase, ningún UUID.
        assertThat(reporte.discardedDetails()).singleElement().isEqualTo(
                "ADD_ATTRIBUTE 'telefono' descartado: la clase 'Cliente' fue eliminada por otro "
                        + "colaborador.");
        verify(operationRepository, never()).save(any());
        verify(sequenceService, never()).reservar(any(), anyInt());
    }

    @Test
    @DisplayName("§9.2: UPDATE_* se descarta si el objetivo fue eliminado")
    void updateSobreObjetivoBorrado() {
        String cliente = idDe("Cliente");
        diagrama.setCurrentState(sinLaClase(cliente));

        ReconciliationReport reporte = reconciliar(
                op("RENAME_CLASS", cliente, "m1", Map.of("name", "ClienteVIP")));

        assertThat(reporte.discardedCount()).isEqualTo(1);
        assertThat(reporte.discardedDetails().get(0)).contains("eliminado por otro colaborador");
    }

    @Test
    @DisplayName("§9.2: ADD_CLASS con un id que ya existe se descarta por colisión")
    void colisionDeIdentificadores() {
        String cita = idDe("Cita");

        ReconciliationReport reporte = reconciliar(
                op("ADD_CLASS", null, "m1", Map.of("id", cita, "name", "CitaDuplicada")));

        assertThat(reporte.discardedCount()).isEqualTo(1);
        assertThat(reporte.discardedDetails().get(0))
                .isEqualTo("ADD_CLASS 'CitaDuplicada' descartado: ya existe un elemento con ese "
                        + "identificador.");
    }

    @Test
    @DisplayName("§9.2: DELETE_* gana siempre, incluso sobre lo que otro modificó")
    void borrarGanaSiempre() {
        String cita = idDe("Cita");
        long relacionesDeCita = inicial.relationships().stream()
                .filter(r -> cita.equals(r.sourceClassId()) || cita.equals(r.targetClassId()))
                .count();

        ReconciliationReport reporte = reconciliar(
                op("DELETE_CLASS", cita, "m1", Map.of()));

        assertThat(reporte.appliedCount()).isEqualTo(1);
        assertThat(reporte.snapshot().classes()).noneMatch(c -> c.id().equals(cita));
        // "elimina y remueve relaciones asociadas" (§9.2)
        assertThat(reporte.snapshot().relationships())
                .hasSize(inicial.relationships().size() - (int) relacionesDeCita)
                .noneMatch(r -> cita.equals(r.sourceClassId()) || cita.equals(r.targetClassId()));
    }

    @Test
    @DisplayName("Un borrado guarda en la bitácora el nombre de lo que borró")
    void elBorradoRegistraElNombre() {
        String cita = idDe("Cita");

        reconciliar(op("DELETE_CLASS", cita, "m1", Map.of()));

        var capturada = org.mockito.ArgumentCaptor.forClass(DiagramOperation.class);
        verify(operationRepository).save(capturada.capture());
        // Es el único momento en que ese nombre existe: después de aplicar el borrado no está
        // en el estado nuevo ni en ningún otro lado. Sin esto, un rebase posterior sólo podría
        // decir "la clase c3383fbd-… fue eliminada", que no le sirve a nadie.
        assertThat(capturada.getValue().getPayload()).contains("\"name\":\"Cita\"");
    }

    @Test
    @DisplayName("Borrar algo que otro ya borró no es un conflicto, pero se informa")
    void borrarLoYaBorrado() {
        String cliente = idDe("Cliente");
        diagrama.setCurrentState(sinLaClase(cliente));

        ReconciliationReport reporte = reconciliar(op("DELETE_CLASS", cliente, "m1", Map.of()));

        assertThat(reporte.appliedCount()).isZero();
        assertThat(reporte.discardedDetails().get(0))
                .contains("ya había sido eliminado por otro colaborador");
    }

    @Test
    @DisplayName("Una operación que el servidor no implementa se descarta con aviso explícito")
    void operacionNoSoportada() {
        ReconciliationReport reporte = reconciliar(
                op("BULK_MERGE", null, "m1", Map.of()),
                op("INVENTADA", null, "m2", Map.of()));

        assertThat(reporte.discardedCount()).isEqualTo(2);
        // Una operación inventada: el servidor no la conoce.
        assertThat(reporte.discardedDetails())
                .anyMatch(d -> d.contains("el servidor no soporta esa operación"));
        // Un BULK_MERGE sí existe, pero NO se puede reconciliar: reemplaza el diagrama entero,
        // así que aplicarlo al reconectar borraría todo lo que los demás hicieron mientras tanto.
        assertThat(reporte.discardedDetails())
                .anyMatch(d -> d.contains("reemplaza el diagrama entero"));
    }

    // ------------------------------------------------------------ idempotencia

    @Test
    @DisplayName("Reenviar el lote entero no duplica nada: cuenta aplicado y no reaplica")
    void reintentoDelLoteCompleto() {
        String cita = idDe("Cita");
        when(operationRepository.findByDiagramIdAndClientMutationId(diagramId, "m1"))
                .thenReturn(Optional.of(new DiagramOperation(
                        diagrama, null, "MOVE_CLASS", cita, "{}", "m1", 7L)));

        ReconciliationReport reporte = reconciliar(
                op("MOVE_CLASS", cita, "m1", Map.of("x", 1.0, "y", 1.0)),
                op("MOVE_CLASS", cita, "m2", Map.of("x", 2.0, "y", 2.0)));

        // La primera ya estaba: cuenta como aplicada —el cambio del usuario está en el
        // diagrama— pero no se vuelve a escribir.
        assertThat(reporte.appliedCount()).isEqualTo(2);
        assertThat(reporte.discardedCount()).isZero();
        verify(operationRepository, times(1)).save(any());
        verify(sequenceService).reservar(diagramId, 1);
    }

    // -------------------------------------------------------- mezcla realista

    @Test
    @DisplayName("Cola mixta: se aplica lo que se puede y se informa cada descarte por separado")
    void colaMixta() {
        String cita = idDe("Cita");
        String cliente = idDe("Cliente");
        diagrama.setCurrentState(sinLaClase(cliente));

        ReconciliationReport reporte = reconciliar(
                op("MOVE_CLASS", cita, "m1", Map.of("x", 5.0, "y", 5.0)),
                op("ADD_ATTRIBUTE", cliente, "m2",
                        Map.of("id", "a1", "name", "telefono", "type", "VARCHAR")),
                op("ADD_CLASS", null, "m3", Map.of("id", "n1", "name", "Factura")),
                op("RENAME_CLASS", cliente, "m4", Map.of("name", "ClienteVIP")));

        assertThat(reporte.appliedCount()).isEqualTo(2);
        assertThat(reporte.discardedCount()).isEqualTo(2);
        assertThat(reporte.discardedDetails()).hasSize(2);
        assertThat(reporte.snapshot().classes()).anyMatch(c -> c.id().equals("n1"));
        // El bloque reservado corresponde sólo a lo aplicado, no a la cola entera.
        verify(sequenceService).reservar(diagramId, 2);
        assertThat(reporte.snapshot().mutationVersion()).isEqualTo(101L);
    }

    @Test
    @DisplayName("El snapshot devuelto es el estado ya reconciliado, no el de partida")
    void elSnapshotEsElEstadoFinal() {
        ReconciliationReport reporte = reconciliar(
                op("ADD_CLASS", null, "m1", Map.of("id", "n1", "name", "Factura")));

        // El cliente acaba de ver que parte de su trabajo se descartó; lo peor que se le puede
        // pedir en ese momento es una segunda petición para saber cómo quedó el diagrama.
        assertThat(reporte.snapshot().classes()).hasSize(inicial.classes().size() + 1);
        assertThat(reporte.snapshot().mutationVersion()).isEqualTo(100L);
    }

    /** Simula que otro colaborador borró esa clase durante la desconexión. */
    private String sinLaClase(String classId) {
        try {
            CanonicalModel recortado = new CanonicalModel(
                    inicial.schemaVersion(), inicial.mutationVersion(), inicial.packages(),
                    inicial.classes().stream().filter(c -> !c.id().equals(classId)).toList(),
                    inicial.relationships().stream()
                            .filter(r -> !classId.equals(r.sourceClassId())
                                    && !classId.equals(r.targetClassId()))
                            .toList());
            return mapper.writeValueAsString(recortado);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
