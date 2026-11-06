package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelcollab.config.JacksonConfig;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.project.model.Project;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU20: la carga del diagrama para el chat. Repositorio simulado; sin base de datos. */
class JpaDiagramContextSourceTest {

    private static final UUID PROYECTO = UUID.randomUUID();
    private static final UUID DIAGRAMA = UUID.randomUUID();
    private static final String ESTADO = "{\"schemaVersion\":\"1.0.0\",\"mutationVersion\":3,\"packages\":[],"
            + "\"classes\":[{\"id\":\"c1\",\"name\":\"Cita\",\"attributes\":[],\"methods\":[]}],"
            + "\"relationships\":[]}";

    private DiagramRepository repo;
    private JpaDiagramContextSource fuente;

    @BeforeEach
    void preparar() {
        repo = mock(DiagramRepository.class);
        fuente = new JpaDiagramContextSource(repo, JacksonConfig.canonicalObjectMapper());
    }

    /**
     * Crea y configura los mocks. Llamarlo SIEMPRE antes de {@code when(...)} y guardar el
     * resultado en una variable: dentro de un {@code thenReturn(...)} da UnfinishedStubbing.
     */
    private Diagram diagrama(UUID proyecto, String estado) {
        Project p = mock(Project.class);
        when(p.getId()).thenReturn(proyecto);
        Diagram d = mock(Diagram.class);
        when(d.getProject()).thenReturn(p);
        when(d.getCurrentState()).thenReturn(estado);
        return d;
    }

    @Test
    @DisplayName("Diagrama del proyecto: se convierte con el mapper canónico")
    void delProyecto() {
        Diagram propio = diagrama(PROYECTO, ESTADO);
        when(repo.findById(DIAGRAMA)).thenReturn(Optional.of(propio));

        Optional<CanonicalModel> modelo = fuente.cargar(DIAGRAMA, PROYECTO);

        assertThat(modelo).isPresent();
        assertThat(modelo.get().classes()).extracting(c -> c.name()).containsExactly("Cita");
        assertThat(modelo.get().mutationVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Diagrama inexistente: vacío")
    void inexistente() {
        when(repo.findById(DIAGRAMA)).thenReturn(Optional.empty());
        assertThat(fuente.cargar(DIAGRAMA, PROYECTO)).isEmpty();
    }

    @Test
    @DisplayName("Diagrama de otro proyecto: vacío, aunque exista")
    void deOtroProyecto() {
        Diagram ajeno = diagrama(UUID.randomUUID(), ESTADO);
        when(repo.findById(DIAGRAMA)).thenReturn(Optional.of(ajeno));

        assertThat(fuente.cargar(DIAGRAMA, PROYECTO)).isEmpty();
        verify(ajeno, never()).getCurrentState();
    }

    @Test
    @DisplayName("current_state corrupto: vacío, no lanza")
    void estadoCorrupto() {
        Diagram corrupto = diagrama(PROYECTO, "{no es json");
        when(repo.findById(DIAGRAMA)).thenReturn(Optional.of(corrupto));
        assertThat(fuente.cargar(DIAGRAMA, PROYECTO)).isEmpty();
    }

    @Test
    @DisplayName("El repositorio falla: vacío, no lanza")
    void repositorioFalla() {
        when(repo.findById(DIAGRAMA)).thenThrow(new IllegalStateException("sin conexión"));
        assertThat(fuente.cargar(DIAGRAMA, PROYECTO)).isEmpty();
    }

    @Test
    @DisplayName("Ids nulos: vacío sin consultar")
    void nulos() {
        assertThat(fuente.cargar(null, PROYECTO)).isEmpty();
        assertThat(fuente.cargar(DIAGRAMA, null)).isEmpty();
        verify(repo, never()).findById(DIAGRAMA);
    }
}
