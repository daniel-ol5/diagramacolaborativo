package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.ai.service.ReintentoAnteSaturacion;
import com.modelcollab.config.JacksonConfig;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * CU19, etapa 4, tanda 1: cliente real del chat contra un servidor HTTP simulado
 * (MockRestServiceServer). Sin red: los reintentos no duermen.
 */
class ProveedorAiChatModelClientTest {

    private static final String CLAVE = "sk-clave-de-prueba-que-no-debe-salir";
    private static final ReintentoAnteSaturacion SIN_ESPERA = new ReintentoAnteSaturacion(d -> {});

    private static final ChatRequest PEDIDO = ChatRequest.of(
            "Sos la IA del equipo.",
            List.of(ChatTurn.user("[Ana]: hola"), ChatTurn.assistant("Hola Ana"), ChatTurn.user("[Ana]: @IA ¿qué es una composición?")),
            "Ana");

    private record Armado(ProveedorAiChatModelClient cliente, MockRestServiceServer servidor) {}

    private static Armado armar(String proveedor, String endpoint) {
        AiProperties props = new AiProperties(proveedor, CLAVE, "modelo-a", "modelo-b", endpoint, 1000);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
        return new Armado(
                new ProveedorAiChatModelClient(props, JacksonConfig.canonicalObjectMapper(), builder, SIN_ESPERA),
                servidor);
    }

    private static String respuestaOpenAi(String texto) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + texto + "\"}}]}";
    }

    @Nested
    @DisplayName("OpenAI")
    class OpenAi {

        private static final String URL = "http://ia.local/v1/chat/completions";

        @Test
        @DisplayName("Varios turnos, texto libre, temperatura 0.4 y 400 tokens por defecto; sin JSON forzado")
        void forma() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(once(), requestTo(URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer " + CLAVE))
                    .andExpect(jsonPath("$.model").value("modelo-a"))
                    .andExpect(jsonPath("$.temperature").value(0.4))
                    .andExpect(jsonPath("$.max_tokens").value(400))
                    .andExpect(jsonPath("$.response_format").doesNotExist())
                    .andExpect(jsonPath("$.messages[0].role").value("system"))
                    .andExpect(jsonPath("$.messages[0].content").value("Sos la IA del equipo."))
                    .andExpect(jsonPath("$.messages[1].role").value("user"))
                    .andExpect(jsonPath("$.messages[1].content").value("[Ana]: hola"))
                    .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                    .andExpect(jsonPath("$.messages[3].role").value("user"))
                    .andRespond(withSuccess(respuestaOpenAi("Ana, es una relación fuerte."), MediaType.APPLICATION_JSON));

            ChatCompletion r = a.cliente().chat(PEDIDO);

            assertThat(r.text()).isEqualTo("Ana, es una relación fuerte.");
            assertThat(r.model()).isEqualTo("modelo-a");
            a.servidor().verify();
        }

        @Test
        @DisplayName("Temperatura y tokens del ChatRequest se respetan")
        void parametros() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(once(), requestTo(URL))
                    .andExpect(jsonPath("$.temperature").value(0.9))
                    .andExpect(jsonPath("$.max_tokens").value(120))
                    .andRespond(withSuccess(respuestaOpenAi("ok"), MediaType.APPLICATION_JSON));

            a.cliente().chat(new ChatRequest("s", List.of(ChatTurn.user("[Ana]: hola")), 0.9, 120, "Ana"));
            a.servidor().verify();
        }

        @Test
        @DisplayName("503 en el principal: reintenta y responde el de respaldo, que queda en model")
        void saturacionUsaRespaldo() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(times(ReintentoAnteSaturacion.INTENTOS), requestTo(URL))
                    .andExpect(jsonPath("$.model").value("modelo-a"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            a.servidor().expect(once(), requestTo(URL))
                    .andExpect(jsonPath("$.model").value("modelo-b"))
                    .andRespond(withSuccess(respuestaOpenAi("desde el respaldo"), MediaType.APPLICATION_JSON));

            ChatCompletion r = a.cliente().chat(PEDIDO);

            assertThat(r.text()).isEqualTo("desde el respaldo");
            assertThat(r.model()).isEqualTo("modelo-b");
            a.servidor().verify();
        }

        @Test
        @DisplayName("429 del proveedor (también en el respaldo): PROVIDER_QUOTA, mensaje aparte")
        void cuotaDelProveedor() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(manyTimes(), requestTo(URL))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

            assertThatThrownBy(() -> a.cliente().chat(PEDIDO))
                    .isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                        assertThat(e.reason()).isEqualTo(AiUnavailableException.Reason.PROVIDER_QUOTA);
                        assertThat(e.getMessage()).contains("límite de uso del proveedor");
                    });
        }

        @Test
        @DisplayName("401 por clave inválida: UNAVAILABLE en un solo intento, sin la clave en el mensaje")
        void claveInvalida() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(once(), requestTo(URL))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":{\"message\":\"Incorrect API key provided: " + CLAVE + "\"}}"));

            assertThatThrownBy(() -> a.cliente().chat(PEDIDO))
                    .isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                        assertThat(e.reason()).isEqualTo(AiUnavailableException.Reason.UNAVAILABLE);
                        assertThat(e.getMessage()).doesNotContain(CLAVE);
                    });
            a.servidor().verify();
        }

        @Test
        @DisplayName("Timeout de lectura: UNAVAILABLE")
        void timeout() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(manyTimes(), requestTo(URL))
                    .andRespond(withException(new SocketTimeoutException("Read timed out")));

            assertThatThrownBy(() -> a.cliente().chat(PEDIDO))
                    .isInstanceOfSatisfying(AiUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(AiUnavailableException.Reason.UNAVAILABLE));
        }

        @Test
        @DisplayName("Respuesta sin texto o que no es JSON: INVALID_RESPONSE")
        void respuestaInutilizable() {
            Armado a = armar("openai", "http://ia.local/v1");
            a.servidor().expect(once(), requestTo(URL))
                    .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> a.cliente().chat(PEDIDO))
                    .isInstanceOfSatisfying(AiUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(AiUnavailableException.Reason.INVALID_RESPONSE));

            Armado b = armar("openai", "http://ia.local/v1");
            b.servidor().expect(once(), requestTo(URL))
                    .andRespond(withSuccess("<html>no</html>", MediaType.TEXT_HTML));
            assertThatThrownBy(() -> b.cliente().chat(PEDIDO))
                    .isInstanceOfSatisfying(AiUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(AiUnavailableException.Reason.INVALID_RESPONSE));
        }
    }

    @Nested
    @DisplayName("Gemini")
    class Gemini {

        @Test
        @DisplayName("systemInstruction, roles user/model, temperatura y maxOutputTokens; sin JSON forzado")
        void forma() {
            Armado a = armar("gemini", "http://ia.local/v1beta");
            a.servidor().expect(once(), requestTo("http://ia.local/v1beta/models/modelo-a:generateContent"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-goog-api-key", CLAVE))
                    .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("Sos la IA del equipo."))
                    .andExpect(jsonPath("$.contents[0].role").value("user"))
                    .andExpect(jsonPath("$.contents[0].parts[0].text").value("[Ana]: hola"))
                    .andExpect(jsonPath("$.contents[1].role").value("model"))
                    .andExpect(jsonPath("$.generationConfig.temperature").value(0.4))
                    .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(400))
                    .andExpect(jsonPath("$.generationConfig.responseMimeType").doesNotExist())
                    .andRespond(withSuccess(
                            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ana, claro.\"}]}}]}",
                            MediaType.APPLICATION_JSON));

            ChatCompletion r = a.cliente().chat(PEDIDO);

            assertThat(r.text()).isEqualTo("Ana, claro.");
            assertThat(r.model()).isEqualTo("modelo-a");
            a.servidor().verify();
        }
    }

    @Test
    @DisplayName("Gemini: turnos consecutivos del mismo rol se unen; OpenAI los recibe separados")
    void turnosConsecutivos() {
        ChatRequest grupal = ChatRequest.of("s", List.of(
                ChatTurn.user("[Ana]: hola"), ChatTurn.user("[Carlos]: buenas"),
                ChatTurn.assistant("Hola a los dos"), ChatTurn.user("[Ana]: @IA ¿qué es UML?")), "Ana");

        Armado g = armar("gemini", "http://ia.local/v1beta");
        g.servidor().expect(once(), requestTo("http://ia.local/v1beta/models/modelo-a:generateContent"))
                .andExpect(jsonPath("$.contents.length()").value(3))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("[Ana]: hola\n\n[Carlos]: buenas"))
                .andExpect(jsonPath("$.contents[1].role").value("model"))
                .andExpect(jsonPath("$.contents[2].parts[0].text").value("[Ana]: @IA ¿qué es UML?"))
                .andRespond(withSuccess(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ana, ok\"}]}}]}",
                        MediaType.APPLICATION_JSON));
        g.cliente().chat(grupal);
        g.servidor().verify();

        Armado o = armar("openai", "http://ia.local/v1");
        o.servidor().expect(once(), requestTo("http://ia.local/v1/chat/completions"))
                .andExpect(jsonPath("$.messages.length()").value(5))
                .andExpect(jsonPath("$.messages[1].content").value("[Ana]: hola"))
                .andExpect(jsonPath("$.messages[2].content").value("[Carlos]: buenas"))
                .andRespond(withSuccess(respuestaOpenAi("Ana, ok"), MediaType.APPLICATION_JSON));
        o.cliente().chat(grupal);
        o.servidor().verify();
    }

    @Test
    @DisplayName("descripcion() nombra proveedor y modelo, nunca la clave; no es simulado")
    void descripcion() {
        ProveedorAiChatModelClient cliente = armar("openai", "http://ia.local/v1").cliente();
        assertThat(cliente.descripcion()).contains("OpenAI").contains("modelo-a").doesNotContain(CLAVE);
        assertThat(cliente.esSimulado()).isFalse();
    }
}
