package com.modelcollab.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.assistant.store.InMemoryChatStore;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.project.model.ProjectRole;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/** CU19, etapa 2c: entrada STOMP del chat, con Mockito y sin base de datos. */
@ExtendWith(MockitoExtension.class)
class ChatStompControllerTest {

    @Mock private ChatService chatService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private ChatStompController controller;

    private final UUID projectId = UUID.randomUUID();
    private final UUID anaId = UUID.randomUUID();
    private final StompPrincipal ana = new StompPrincipal(anaId);
    private final Participante anaEnSesion = Participante.de(anaId, "Ana Pérez");
    private final String destino = "/app/project/" + projectId + "/chat";

    @BeforeEach
    void preparar() {
        controller = new ChatStompController(chatService, messagingTemplate);
    }

    @Test
    @DisplayName("Mensaje válido: va al servicio con el Principal y el Participante de la sesión")
    void mensajeValido() {
        ChatPostRequest cuerpo = new ChatPostRequest("c-1", "hola", null, null);

        controller.enviar(projectId.toString(), cuerpo, sesion(ana, anaEnSesion));

        verify(chatService).postFromUser(eq(projectId), same(ana), same(cuerpo), same(anaEnSesion));
        // La difusión es de StompChatPublisher: el controlador no publica ni avisa errores.
        verifyNoInteractions(messagingTemplate);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ChatErrorCode.class)
    @DisplayName("Rechazo del servicio: StompErrorMessage por /user/queue/errors, sin excepción")
    void rechazo(ChatErrorCode code) {
        ChatPostRequest cuerpo = new ChatPostRequest("c-7", "hola", null, null);
        when(chatService.postFromUser(any(), any(), any(), any()))
                .thenThrow(new ChatRejectedException(code, "motivo para el usuario"));

        controller.enviar(projectId.toString(), cuerpo, sesion(ana, anaEnSesion));

        StompErrorMessage error = errorEnviadoA(anaId);
        assertThat(error.status()).isEqualTo(code.status());
        assertThat(error.code()).isEqualTo(code.name());
        assertThat(error.destination()).isEqualTo(destino);
        assertThat(error.clientMutationId()).isEqualTo("c-7");
        assertThat(error.message()).isEqualTo("motivo para el usuario");
    }

    @Test
    @DisplayName("projectId no UUID: INVALID_PAYLOAD sin llamar al servicio")
    void projectIdNoUuid() {
        controller.enviar("no-uuid", new ChatPostRequest("c-1", "hola", null, null),
                sesion(ana, anaEnSesion));

        assertThat(errorEnviadoA(anaId).code()).isEqualTo("INVALID_PAYLOAD");
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Sin Principal no se llama al servicio ni se avisa a nadie")
    void sinPrincipal() {
        controller.enviar(projectId.toString(), new ChatPostRequest("c-1", "hola", null, null),
                sesion(null, null));

        verifyNoInteractions(chatService, messagingTemplate);
    }

    @Test
    @DisplayName("Cuerpo ilegible o con campos desconocidos: INVALID_PAYLOAD por /user/queue/errors")
    void cuerpoInvalido() {
        StompHeaderAccessor accessor = sesion(ana, anaEnSesion);
        accessor.setDestination(destino);

        controller.cuerpoInvalido(new MessageConversionException("campo desconocido"), accessor);

        StompErrorMessage error = errorEnviadoA(anaId);
        assertThat(error.status()).isEqualTo(400);
        assertThat(error.code()).isEqualTo("INVALID_PAYLOAD");
        assertThat(error.destination()).isEqualTo(destino);
        verify(chatService, never()).postFromUser(any(), any(), any(), any());
    }

    // ------------------------------------------------- el autor no sale del cuerpo

    @Test
    @DisplayName("Un cuerpo con authorId/authorName ni siquiera se deserializa (ObjectMapper canónico)")
    void cuerpoConAutorSeRechaza() {
        String json = "{\"clientMessageId\":\"c-1\",\"text\":\"hola\","
                + "\"authorId\":\"" + UUID.randomUUID() + "\",\"authorName\":\"Impostor\"}";

        assertThatThrownBy(() -> JacksonConfig.canonicalObjectMapper()
                        .readValue(json, ChatPostRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("Con el servicio real: el autor guardado es el del Principal, aunque la sesión traiga otro")
    void autorDelPrincipalConServicioReal() {
        UUID betoId = UUID.randomUUID();
        InMemoryChatStore store = new InMemoryChatStore();
        List<ChatMessage> difundidos = new ArrayList<>();
        ChatPublisher publicador = new ChatPublisher() {
            @Override public void publishMessage(ChatMessage message) { difundidos.add(message); }
            @Override public void publishTyping(String p, String u, String n, boolean a) {}
        };
        ChatParticipants participantes = new ChatParticipants() {
            @Override public Optional<ProjectRole> roleIn(UUID p, UUID u) {
                return Optional.of(ProjectRole.VIEWER);
            }
            @Override public Optional<String> fullName(UUID u) {
                return Optional.of(u.equals(anaId) ? "Ana Pérez" : "Beto Ruiz");
            }
            @Override public boolean diagramBelongsToProject(UUID d, UUID p) {
                return false;
            }
        };
        ChatService real = new ChatService(store, publicador, participantes, List.of());
        ChatStompController conServicioReal = new ChatStompController(real, messagingTemplate);

        // La sesión es de Ana, pero el Participante guardado dice ser Beto: manda el Principal.
        conServicioReal.enviar(projectId.toString(), new ChatPostRequest("c-1", "hola", null, null),
                sesion(ana, Participante.de(betoId, "Beto Ruiz")));

        assertThat(difundidos).singleElement().satisfies(m -> {
            assertThat(m.authorId()).isEqualTo(anaId.toString());
            assertThat(m.authorName()).isEqualTo("Ana Pérez");
            assertThat(m.authorColor()).isEqualTo(Participante.de(anaId, "Ana Pérez").color());
        });
        verifyNoInteractions(messagingTemplate);
    }

    // ------------------------------------------------------------------ soporte

    private static StompHeaderAccessor sesion(StompPrincipal principal, Participante participante) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        Map<String, Object> atributos = new HashMap<>();
        if (participante != null) {
            atributos.put("participante", participante);
        }
        accessor.setSessionAttributes(atributos);
        if (principal != null) {
            accessor.setUser(principal);
        }
        return accessor;
    }

    private StompErrorMessage errorEnviadoA(UUID userId) {
        ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate)
                .convertAndSendToUser(eq(userId.toString()), eq("/queue/errors"), cuerpo.capture());
        assertThat(cuerpo.getValue()).isInstanceOf(StompErrorMessage.class);
        return (StompErrorMessage) cuerpo.getValue();
    }
}
