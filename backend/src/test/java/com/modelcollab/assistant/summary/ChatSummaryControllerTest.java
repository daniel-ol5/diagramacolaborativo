package com.modelcollab.assistant.summary;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.project.model.ProjectRole;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ChatSummaryControllerTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    @Mock
    private ChatSummaryService summaryService;

    @Mock
    private ChatParticipants participants;

    private MockMvc mvc;
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Principal principal;

    @BeforeEach
    void setUp() {
        ChatSummaryController controller = new ChatSummaryController(summaryService, participants);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        principal = new UsernamePasswordAuthenticationToken(userId.toString(), "n/a");
    }

    @Test
    @DisplayName("POST /summarize: 200 cuando el rol es OWNER")
    void summarizeOwnerOk() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.OWNER));

        ChatSummaryResponse response = new ChatSummaryResponse(
                "s1", projectId.toString(), 15L, 1L, 14L, 14,
                List.of("Decisión 1"), List.of("Duda 1"), "Texto del resumen", Instant.now());
        when(summaryService.summarize(projectId, ChatSummaryTrigger.MANUAL)).thenReturn(Optional.of(response));

        mvc.perform(post("/api/v1/projects/{id}/chat/summarize", projectId).principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.fromSeq").value(1))
                .andExpect(jsonPath("$.toSeq").value(14))
                .andExpect(jsonPath("$.decisions[0]").value("Decisión 1"));
    }

    @Test
    @DisplayName("POST /summarize: 200 cuando el rol es EDITOR")
    void summarizeEditorOk() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.EDITOR));

        ChatSummaryResponse response = new ChatSummaryResponse(
                "s2", projectId.toString(), 20L, 15L, 19L, 5,
                List.of("Acuerdo clase Pedido"), List.of(), "Texto del resumen 2", Instant.now());
        when(summaryService.summarize(projectId, ChatSummaryTrigger.MANUAL)).thenReturn(Optional.of(response));

        mvc.perform(post("/api/v1/projects/{id}/chat/summarize", projectId).principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s2"))
                .andExpect(jsonPath("$.fromSeq").value(15))
                .andExpect(jsonPath("$.toSeq").value(19));
    }

    @Test
    @DisplayName("POST /summarize: 403 Forbidden cuando el rol es VIEWER")
    void summarizeViewerForbidden() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.VIEWER));

        mvc.perform(post("/api/v1/projects/{id}/chat/summarize", projectId).principal(principal))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /summarize: 403 Forbidden cuando el usuario no es miembro del proyecto")
    void summarizeNoMiembroForbidden() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/projects/{id}/chat/summarize", projectId).principal(principal))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /summarize: 204 No Content cuando no hay mensajes que resumir")
    void summarizeSinMensajesNoContent() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.EDITOR));
        when(summaryService.summarize(projectId, ChatSummaryTrigger.MANUAL)).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/projects/{id}/chat/summarize", projectId).principal(principal))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /summaries/latest: 200 devuelve el último resumen para cualquier miembro")
    void getLatestSummaryOk() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.VIEWER));

        ChatSummaryResponse response = new ChatSummaryResponse(
                "s-latest", projectId.toString(), 50L, 1L, 49L, 49,
                List.of("D1"), List.of("Q1"), "Texto del último resumen", Instant.now());
        when(summaryService.getLatestSummary(projectId)).thenReturn(Optional.of(response));

        mvc.perform(get("/api/v1/projects/{id}/chat/summaries/latest", projectId).principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s-latest"))
                .andExpect(jsonPath("$.totalMessages").value(49));
    }

    @Test
    @DisplayName("GET /summaries/latest: 404 Not Found si no existe ningún resumen previo")
    void getLatestSummaryNotFound() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(summaryService.getLatestSummary(projectId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/projects/{id}/chat/summaries/latest", projectId).principal(principal))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /summaries/latest: 403 Forbidden si el usuario no es miembro")
    void getLatestSummaryNoMiembroForbidden() throws Exception {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/projects/{id}/chat/summaries/latest", projectId).principal(principal))
                .andExpect(status().isForbidden());
    }
}

