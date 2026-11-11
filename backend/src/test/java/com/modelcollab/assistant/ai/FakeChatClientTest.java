package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.config.JacksonConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU19, etapa 4, tanda 1: cliente simulado, records y elección del cliente. */
class FakeChatClientTest {

    private final FakeChatClient fake = new FakeChatClient();

    @Test
    @DisplayName("Determinista: \"Hola {nombre}, (respuesta simulada)\" con el nombre de pila de quien preguntó")
    void saludaPorNombre() {
        ChatRequest pedido = ChatRequest.of("s", List.of(ChatTurn.user("[Ana Pérez]: @IA hola")), "Ana");

        assertThat(fake.chat(pedido)).isEqualTo(new ChatCompletion("Hola Ana, (respuesta simulada)", "simulado"));
        assertThat(fake.chat(pedido)).isEqualTo(fake.chat(pedido));
        assertThat(fake.esSimulado()).isTrue();
    }

    @Test
    @DisplayName("Sin nombre no falla")
    void sinNombre() {
        assertThat(fake.chat(ChatRequest.of("s", List.of(), null)).text()).isEqualTo("Hola, (respuesta simulada)");
        assertThat(fake.chat(null).text()).isEqualTo("Hola, (respuesta simulada)");
    }

    @Test
    @DisplayName("ChatRequest: 0.4 y 400 por defecto, y la lista de turnos es inmutable")
    void valoresPorDefecto() {
        ChatRequest pedido = new ChatRequest(null, null, null, null, "Ana");

        assertThat(pedido.temperature()).isEqualTo(0.4);
        assertThat(pedido.maxTokens()).isEqualTo(400);
        assertThat(pedido.system()).isEmpty();
        assertThat(pedido.turns()).isEmpty();
    }

    @Test
    @DisplayName("AiChatConfig: sin clave, simulado; con clave, el proveedor")
    void eleccionDelCliente() {
        AiChatConfig config = new AiChatConfig();
        AiProperties sinClave = new AiProperties("openai", "", null, null, null, null);
        AiProperties conClave = new AiProperties("openai", "sk-x", null, null, null, null);

        AiChatModelClient clienteSinClave = config.aiChatModelClient(sinClave, JacksonConfig.canonicalObjectMapper());
        AiChatModelClient clienteConClave = config.aiChatModelClient(conClave, JacksonConfig.canonicalObjectMapper());

        assertThat(clienteSinClave).isInstanceOf(AssistantChatModelRouter.class);
        assertThat(((AssistantChatModelRouter) clienteSinClave).cloudClient()).isInstanceOf(FakeChatClient.class);
        assertThat(clienteConClave).isInstanceOf(AssistantChatModelRouter.class);
        assertThat(((AssistantChatModelRouter) clienteConClave).cloudClient()).isInstanceOf(ProveedorAiChatModelClient.class);
    }
}
