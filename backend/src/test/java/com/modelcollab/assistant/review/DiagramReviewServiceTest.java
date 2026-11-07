package com.modelcollab.assistant.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.assistant.ai.AiChatModelClient;
import com.modelcollab.assistant.ai.ChatCompletion;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.MetamodelValidator;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DiagramReviewServiceTest {

    @Mock
    private DiagramRepository diagramRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private AiChatModelClient aiClient;

    @Mock
    private ChatService chatService;

    private DiagramReviewService service;

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();
    private final UUID diagramId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MetamodelValidator validator = new MetamodelValidator(new IdentifierSanitizer());
        DiagramReviewRulesEngine rulesEngine = new DiagramReviewRulesEngine(validator);

        service = new DiagramReviewService(
                diagramRepository,
                projectMemberRepository,
                rulesEngine,
                mapper,
                aiClient,
                chatService);
    }

    private String leerFixture(String recurso) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(recurso)) {
            assertThat(in).as("falta fixture %s", recurso).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("404 cuando el diagrama no existe")
    void diagramaNoExisteLanza404() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviewDiagram(diagramId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("403 cuando el usuario no es miembro del proyecto")
    void noMiembroLanza403() {
        Project project = new Project("Proyecto Demo", null, null);
        ReflectionTestUtils.setField(project, "id", projectId);
        Diagram diagram = new Diagram(project, "Mi Diagrama");
        ReflectionTestUtils.setField(diagram, "id", diagramId);

        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(diagram));
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviewDiagram(diagramId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("Revisión con problemas: analiza, consulta IA para comentario de mentor y publica tarjeta")
    void revisionConProblemasPublicaTarjeta() throws Exception {
        Project project = new Project("EdgeCases", null, null);
        ReflectionTestUtils.setField(project, "id", projectId);

        Diagram diagram = new Diagram(project, "EdgeCases");
        ReflectionTestUtils.setField(diagram, "id", diagramId);
        diagram.setMutationVersion(5L);
        diagram.setCurrentState(leerFixture("edge-cases.json"));

        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(diagram));
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(ProjectRole.EDITOR));
        when(aiClient.chat(any())).thenReturn(new ChatCompletion("Consejo del Mentor: revisa la clase SinAtributos.", "test-model"));

        DiagramReviewResponse response = service.reviewDiagram(diagramId, userId);

        assertThat(response.totalErrors()).isZero();
        assertThat(response.totalWarnings()).isGreaterThan(0);
        assertThat(response.diagramVersion()).isEqualTo(5L);
        assertThat(response.mentorComment()).contains("Consejo del Mentor");

        // Verifica que se publicó la tarjeta en el chat
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(java.util.Map.class);

        verify(chatService).postCard(
                eq(projectId),
                eq(diagramId.toString()),
                eq(ChatMessageKind.REVIEW),
                textCaptor.capture(),
                metaCaptor.capture());

        assertThat(textCaptor.getValue()).contains("Revisión del diagrama (v5)");
        assertThat(metaCaptor.getValue())
                .containsEntry("type", "DIAGRAM_REVIEW")
                .containsKey("score")
                .containsKey("issues");
    }

    @Test
    @DisplayName("Revisión sin problemas: felicita al equipo sin necesidad de IA")
    void revisionSinProblemasFelicita() throws Exception {
        Project project = new Project("Barberia", null, null);
        ReflectionTestUtils.setField(project, "id", projectId);

        Diagram diagram = new Diagram(project, "Barberia");
        ReflectionTestUtils.setField(diagram, "id", diagramId);
        diagram.setMutationVersion(2L);
        diagram.setCurrentState(leerFixture("reference-diagrams/barberia.json"));

        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(diagram));
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(ProjectRole.OWNER));

        DiagramReviewResponse response = service.reviewDiagram(diagramId, userId);

        assertThat(response.totalErrors()).isZero();
        assertThat(response.score()).isGreaterThanOrEqualTo(80);
        assertThat(response.mentorComment()).contains("cumple con las convenciones");
    }
}
