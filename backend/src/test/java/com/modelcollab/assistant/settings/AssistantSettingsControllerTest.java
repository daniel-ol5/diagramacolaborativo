package com.modelcollab.assistant.settings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.assistant.ai.AssistantLevel;
import com.modelcollab.assistant.ai.AssistantMode;
import com.modelcollab.assistant.ai.AssistantProvider;
import com.modelcollab.assistant.ai.AssistantSettings;
import com.modelcollab.config.JacksonConfig;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AssistantSettingsControllerTest {

    private final ObjectMapper mapper = JacksonConfig.canonicalObjectMapper();

    @Mock
    private AssistantSettingsService service;

    private MockMvc mvc;
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private Principal principal;

    @BeforeEach
    void setUp() {
        AssistantSettingsController controller = new AssistantSettingsController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        principal = new UsernamePasswordAuthenticationToken(userId.toString(), "n/a");
    }

    @Test
    @DisplayName("GET /api/v1/projects/{id}/assistant-settings: 200 devuelve los ajustes")
    void getAjustesOk() throws Exception {
        AssistantSettingsResponse res = new AssistantSettingsResponse(
                AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.CLOUD,
                null, 400, 30, true);

        when(service.getSettings(projectId, userId)).thenReturn(res);

        mvc.perform(get("/api/v1/projects/{id}/assistant-settings", projectId).principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ON_MENTION"))
                .andExpect(jsonPath("$.level").value("INTERMEDIATE"))
                .andExpect(jsonPath("$.provider").value("CLOUD"))
                .andExpect(jsonPath("$.canEdit").value(true));
    }

    @Test
    @DisplayName("PUT /api/v1/projects/{id}/assistant-settings: 200 actualiza ajustes")
    void putAjustesOk() throws Exception {
        AssistantSettingsUpdateRequest req = new AssistantSettingsUpdateRequest(
                AssistantMode.OFF, AssistantLevel.ADVANCED, AssistantProvider.CLOUD, null, 400, 30);
        AssistantSettingsResponse res = new AssistantSettingsResponse(
                AssistantMode.OFF, AssistantLevel.ADVANCED, AssistantProvider.CLOUD, null, 400, 30, true);

        when(service.updateSettings(eq(projectId), eq(userId), any())).thenReturn(res);

        mvc.perform(put("/api/v1/projects/{id}/assistant-settings", projectId)
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("OFF"))
                .andExpect(jsonPath("$.level").value("ADVANCED"));
    }

    @Test
    @DisplayName("PUT /api/v1/projects/{id}/assistant-settings: si no es OWNER devuelve 403")
    void putAjustesForbidden() throws Exception {
        AssistantSettingsUpdateRequest req = new AssistantSettingsUpdateRequest(
                AssistantMode.OFF, AssistantLevel.ADVANCED, AssistantProvider.CLOUD, null, 400, 30);

        when(service.updateSettings(eq(projectId), eq(userId), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo el propietario puede modificar"));

        mvc.perform(put("/api/v1/projects/{id}/assistant-settings", projectId)
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}

