package com.modelcollab.assistant.review;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.config.JacksonConfig;
import java.security.Principal;
import java.util.List;
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
class DiagramReviewControllerTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    @Mock
    private DiagramReviewService reviewService;

    private MockMvc mvc;
    private final UUID diagramId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Principal principal;

    @BeforeEach
    void setUp() {
        DiagramReviewController controller = new DiagramReviewController(reviewService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        principal = new UsernamePasswordAuthenticationToken(userId.toString(), "n/a");
    }

    @Test
    @DisplayName("POST /api/v1/diagrams/{id}/review llama al servicio y devuelve 200 con el reporte")
    void postReviewExitoso() throws Exception {
        DiagramReviewResponse response = new DiagramReviewResponse(
                85, 0, 1, 1, 3L, "Buen trabajo en general.", List.of());

        when(reviewService.reviewDiagram(diagramId, userId)).thenReturn(response);

        mvc.perform(post("/api/v1/diagrams/{diagramId}/review", diagramId).principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(85))
                .andExpect(jsonPath("$.diagramVersion").value(3))
                .andExpect(jsonPath("$.mentorComment").value("Buen trabajo en general."));

        verify(reviewService).reviewDiagram(diagramId, userId);
    }
}

