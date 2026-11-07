package com.modelcollab.assistant.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelcollab.assistant.ai.AiChatModelClient;
import com.modelcollab.assistant.ai.ChatCompletion;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatSummaryServiceTest {

    @Mock
    private ChatStore chatStore;

    @Mock
    private ChatService chatService;

    @Mock
    private AiChatModelClient aiClient;

    private ChatSummaryService summaryService;

    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        summaryService = new ChatSummaryService(chatStore, chatService, aiClient);
    }

    private ChatMessage userMsg(String id, long seq, String authorName, String text) {
        return new ChatMessage(
                id,
                projectId.toString(),
                null,
                seq,
                ChatAuthorType.USER,
                "u-" + id,
                authorName,
                "#111222",
                null,
                null,
                null,
                null,
                ChatMessageKind.CHAT,
                text,
                Instant.now(),
                null
        );
    }

    private ChatMessage summaryMsg(String id, long seq, String text, Map<String, Object> meta) {
        return new ChatMessage(
                id,
                projectId.toString(),
                null,
                seq,
                ChatAuthorType.AI,
                "ai",
                "ModelCollab IA",
                null,
                null,
                null,
                null,
                null,
                ChatMessageKind.SUMMARY,
                text,
                Instant.now(),
                meta
        );
    }

    @Test
    @DisplayName("Sin mensajes en el chat devuelve Optional.empty")
    void sinMensajesDevuelveVacio() {
        when(chatStore.latest(projectId.toString(), 200)).thenReturn(List.of());

        Optional<ChatSummaryResponse> res = summaryService.summarize(projectId, ChatSummaryTrigger.MANUAL);

        assertThat(res).isEmpty();
    }

    @Test
    @DisplayName("Con mensajes y respuesta JSON de IA genera y publica tarjeta de resumen")
    void conMensajesSintetizaConIaYPublicaTarjeta() {
        List<ChatMessage> mensajes = List.of(
                userMsg("m1", 1L, "Ana", "Definamos la clase Persona con nombre y dni"),
                userMsg("m2", 2L, "Bob", "¿Alumno debería heredar de Persona?"),
                userMsg("m3", 3L, "Ana", "Sí, queda acordado que Alumno hereda de Persona")
        );
        when(chatStore.latest(projectId.toString(), 200)).thenReturn(mensajes);

        String jsonIa = """
                {
                  "decisiones": ["Alumno hereda de Persona con nombre y dni"],
                  "preguntasAbiertas": ["¿Qué atributos propios tendrá Alumno?"]
                }
                """;
        when(aiClient.chat(any())).thenReturn(new ChatCompletion(jsonIa, "gpt-test"));

        ChatMessage tarjetaPublicada = summaryMsg(
                "sum-1",
                4L,
                "Texto resumen",
                Map.of(
                        "type", "CHAT_SUMMARY",
                        "fromSeq", 1L,
                        "toSeq", 3L,
                        "totalMessages", 3,
                        "decisions", List.of("Alumno hereda de Persona con nombre y dni"),
                        "openQuestions", List.of("¿Qué atributos propios tendrá Alumno?")
                )
        );
        when(chatService.postCard(eq(projectId), any(), eq(ChatMessageKind.SUMMARY), any(), any()))
                .thenReturn(tarjetaPublicada);

        Optional<ChatSummaryResponse> res = summaryService.summarize(projectId, ChatSummaryTrigger.MANUAL);

        assertThat(res).isPresent();
        ChatSummaryResponse response = res.get();
        assertThat(response.fromSeq()).isEqualTo(1L);
        assertThat(response.toSeq()).isEqualTo(3L);
        assertThat(response.decisions()).contains("Alumno hereda de Persona con nombre y dni");
        assertThat(response.openQuestions()).contains("¿Qué atributos propios tendrá Alumno?");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(chatService).postCard(eq(projectId), any(), eq(ChatMessageKind.SUMMARY), any(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertThat(meta.get("type")).isEqualTo("CHAT_SUMMARY");
        assertThat(meta.get("fromSeq")).isEqualTo(1L);
        assertThat(meta.get("toSeq")).isEqualTo(3L);
    }

    @Test
    @DisplayName("Cuando la IA falla se activa el fallback determinista y publica tarjeta")
    void cuandoIaFallaUsaFallbackDeterminista() {
        List<ChatMessage> mensajes = List.of(
                userMsg("m1", 1L, "Carlos", "Queda acordado agregar la clase Matricula"),
                userMsg("m2", 2L, "Diana", "¿Quién tiene la relación con Pago?")
        );
        when(chatStore.latest(projectId.toString(), 200)).thenReturn(mensajes);
        when(aiClient.chat(any())).thenThrow(new RuntimeException("Simulated AI error"));

        ChatMessage tarjetaPublicada = summaryMsg(
                "sum-2",
                3L,
                "Texto resumen fallback",
                Map.of(
                        "type", "CHAT_SUMMARY",
                        "fromSeq", 1L,
                        "toSeq", 2L,
                        "totalMessages", 2,
                        "decisions", List.of("Carlos: Queda acordado agregar la clase Matricula"),
                        "openQuestions", List.of("¿Quién tiene la relación con Pago?")
                )
        );
        when(chatService.postCard(eq(projectId), any(), eq(ChatMessageKind.SUMMARY), any(), any()))
                .thenReturn(tarjetaPublicada);

        Optional<ChatSummaryResponse> res = summaryService.summarize(projectId, ChatSummaryTrigger.FREQUENCY);

        assertThat(res).isPresent();
        assertThat(res.get().decisions()).hasSize(1);
        assertThat(res.get().openQuestions()).hasSize(1);
    }

    @Test
    @DisplayName("latestSummary consulta el caché o el último mensaje tipo SUMMARY")
    void latestSummaryRetornaUltimoRegistrado() {
        ChatMessage tarjeta = summaryMsg(
                "sum-3",
                10L,
                "Último resumen",
                Map.of(
                        "type", "CHAT_SUMMARY",
                        "fromSeq", 1L,
                        "toSeq", 9L,
                        "totalMessages", 9,
                        "decisions", List.of("Decisión 1"),
                        "openQuestions", List.of("Pregunta 1")
                )
        );
        when(chatStore.latest(projectId.toString(), 100)).thenReturn(List.of(tarjeta));

        var data = summaryService.latestSummary(projectId);
        assertThat(data).isPresent();
        assertThat(data.get().fromSeq()).isEqualTo(1L);
        assertThat(data.get().toSeq()).isEqualTo(9L);
        assertThat(data.get().decisions()).contains("Decisión 1");

        var response = summaryService.getLatestSummary(projectId);
        assertThat(response).isPresent();
        assertThat(response.get().seq()).isEqualTo(10L);
    }
}
