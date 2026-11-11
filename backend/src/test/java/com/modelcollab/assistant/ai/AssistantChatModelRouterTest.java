package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AssistantChatModelRouterTest {

    private final AiChatModelClient cloudClient = mock(AiChatModelClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantChatModelRouter router =
            new AssistantChatModelRouter(cloudClient, objectMapper, "qwen2.5-coder:3b");

    @Test
    @DisplayName("Normalización de URLs de Ollama agrega /v1 y limpia slashes")
    void normalizarEndpointLocal() {
        assertThat(AssistantChatModelRouter.normalizarEndpointLocal(null))
                .isEqualTo("http://localhost:11434/v1");
        assertThat(AssistantChatModelRouter.normalizarEndpointLocal(""))
                .isEqualTo("http://localhost:11434/v1");
        assertThat(AssistantChatModelRouter.normalizarEndpointLocal("http://localhost:11434"))
                .isEqualTo("http://localhost:11434/v1");
        assertThat(AssistantChatModelRouter.normalizarEndpointLocal("http://localhost:11434/"))
                .isEqualTo("http://localhost:11434/v1");
        assertThat(AssistantChatModelRouter.normalizarEndpointLocal("http://host.docker.internal:11434/v1"))
                .isEqualTo("http://host.docker.internal:11434/v1");
        assertThat(AssistantChatModelRouter.normalizarEndpointLocal("http://host.docker.internal:11434/v1/"))
                .isEqualTo("http://host.docker.internal:11434/v1");
    }

    @Test
    @DisplayName("Ajustes nulos o proveedor CLOUD devuelven el cliente en la nube")
    void clienteParaCloud() {
        assertThat(router.clientePara(null)).isSameAs(cloudClient);

        AssistantSettings settingsCloud = new AssistantSettings(
                AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.CLOUD);
        assertThat(router.clientePara(settingsCloud)).isSameAs(cloudClient);
    }

    @Test
    @DisplayName("Proveedor LOCAL devuelve cliente configurado para Ollama y lo cachea")
    void clienteParaLocal() {
        AssistantSettings settingsLocal = new AssistantSettings(
                AssistantMode.ON_MENTION,
                AssistantLevel.INTERMEDIATE,
                AssistantProvider.LOCAL,
                "http://localhost:11434",
                400,
                30);

        AiChatModelClient clienteLocal = router.clientePara(settingsLocal);
        assertThat(clienteLocal).isNotSameAs(cloudClient);
        assertThat(clienteLocal.descripcion()).contains("OpenAI chat (qwen2.5-coder:3b)");

        // Segunda llamada con mismo endpoint devuelve la misma instancia cacheada
        AiChatModelClient cacheado = router.clientePara(settingsLocal);
        assertThat(cacheado).isSameAs(clienteLocal);
    }

    @Test
    @DisplayName("Delegación por defecto delega en el cliente de la nube")
    void delegacionPorDefecto() {
        when(cloudClient.descripcion()).thenReturn("Mock Cloud");
        when(cloudClient.esSimulado()).thenReturn(true);

        assertThat(router.descripcion()).isEqualTo("Mock Cloud");
        assertThat(router.esSimulado()).isTrue();

        ChatRequest req = ChatRequest.of("sys", java.util.List.of(), "User");
        router.chat(req);
        verify(cloudClient).chat(req);
    }
}

