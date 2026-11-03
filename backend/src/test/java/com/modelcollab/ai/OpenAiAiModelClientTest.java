package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.ai.service.OpenAiAiModelClient;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.vision.service.OpenAiVisionModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAiAiModelClientTest {

    @Test
    @DisplayName("AiProperties auto-configura OpenAI por defecto")
    void propiedadesPorDefectoOpenAi() {
        AiProperties props = new AiProperties(null, "sk-test-key", null, null, null, null);
        assertThat(props.esOpenAi()).isTrue();
        assertThat(props.model()).isEqualTo("gpt-4o-mini");
        assertThat(props.modeloDeRespaldo()).isEqualTo("gpt-4o");
        assertThat(props.endpoint()).isEqualTo("https://api.openai.com/v1");
        assertThat(props.hayClave()).isTrue();
    }

    @Test
    @DisplayName("AiProperties detecta clave de Gemini si empieza con AIza y no se especificó proveedor")
    void propiedadesAutoDetectaGemini() {
        AiProperties props = new AiProperties(null, "AIzaSyDummyKey", null, null, null, null);
        assertThat(props.esOpenAi()).isFalse();
        assertThat(props.model()).isEqualTo("gemini-3-flash-preview");
        assertThat(props.endpoint()).isEqualTo("https://generativelanguage.googleapis.com/v1beta");
    }

    @Test
    @DisplayName("OpenAiAiModelClient declara correctamente su descripción y que no es simulado")
    void propiedadesClienteOpenAi() {
        AiProperties props = new AiProperties("openai", "sk-12345", "gpt-4o-mini", "gpt-4o", "https://api.openai.com/v1", 30000);
        OpenAiAiModelClient cliente = new OpenAiAiModelClient(props, JacksonConfig.canonicalObjectMapper());

        assertThat(cliente.esSimulado()).isFalse();
        assertThat(cliente.descripcion()).contains("OpenAI").contains("gpt-4o-mini");
    }

    @Test
    @DisplayName("OpenAiVisionModelClient declara correctamente su descripción y que no es simulado")
    void propiedadesVisionClienteOpenAi() {
        AiProperties props = new AiProperties("openai", "sk-12345", "gpt-4o-mini", "gpt-4o", "https://api.openai.com/v1", 30000);
        OpenAiVisionModelClient cliente = new OpenAiVisionModelClient(props, JacksonConfig.canonicalObjectMapper());

        assertThat(cliente.esSimulado()).isFalse();
        assertThat(cliente.descripcion()).contains("OpenAI multimodal").contains("gpt-4o-mini");
    }
}
