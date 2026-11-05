package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * CU19, etapa 2b: arbitraje del chat en el interceptor STOMP, y regresión de lo que ya existía.
 * Igual que {@link StompChannelInterceptorTest}: repositorios mockeados, sin base de datos.
 */
@ExtendWith(MockitoExtension.class)
class StompChannelInterceptorChatTest {

    @Mock private com.modelcollab.auth.service.JwtService jwtService;
    @Mock private UserRepository userRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MessageChannel canal;

    private StompChannelInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();
    private final String sendChat = "/app/project/" + projectId + "/chat";
    private final String topicChat = "/topic/projects/" + projectId + "/chat";

    /** Compartido entre frames: en una sesión STOMP real es el mismo mapa. */
    private final Map<String, Object> atributosDeSesion = new HashMap<>();

    @BeforeEach
    void prepararInterceptor() {
        interceptor =
                new StompChannelInterceptor(
                        jwtService,
                        userRepository,
                        projectMemberRepository,
                        messagingTemplate,
                        JacksonConfig.canonicalObjectMapper());
    }

    // ============================================================ regresión

    @Nested
    @DisplayName("Regresión: lo que no es del chat se comporta igual que antes")
    class Regresion {

        @Test
        @DisplayName("SUBSCRIBE a /topic/diagrams/{id} sigue pasando SIN control (hueco conocido)")
        void subscribeDiagramaSinControl() {
            Message<?> conSesion =
                    frame(StompCommand.SUBSCRIBE, "/topic/diagrams/" + diagramId, "", true);
            Message<?> sinSesion =
                    frame(StompCommand.SUBSCRIBE, "/topic/diagrams/" + diagramId, "", false);

            assertThat(interceptor.preSend(conSesion, canal)).isSameAs(conSesion);
            assertThat(interceptor.preSend(sinSesion, canal)).isSameAs(sinSesion);
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
            assertThat(atributosDeSesion).isEmpty();
        }

        @Test
        @DisplayName("SUBSCRIBE a /user/queue/errors sigue pasando sin control")
        void subscribeColaDeErrores() {
            Message<?> mensaje = frame(StompCommand.SUBSCRIBE, "/user/queue/errors", "", true);

            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
        }

        @Test
        @DisplayName("SEND a /app/diagram/{id}/mutate: EDITOR pasa, por la consulta y caché de siempre")
        void sendDiagramaEditor() {
            when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                    .thenReturn(Optional.of(ProjectRole.EDITOR));

            Message<?> mensaje = frameMutate("mut-1");
            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);

            verify(projectMemberRepository, never()).findByIdProjectIdAndIdUserId(any(), any());
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            assertThat(atributosDeSesion).containsOnlyKeys("role:" + diagramId);
        }

        @Test
        @DisplayName("SEND a /app/diagram/{id}/mutate: VIEWER sigue recibiendo FORBIDDEN_ROLE 403")
        void sendDiagramaViewer() {
            when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                    .thenReturn(Optional.of(ProjectRole.VIEWER));

            assertThat(interceptor.preSend(frameMutate("mut-42"), canal)).isNull();

            StompErrorMessage error = errorEnviado();
            assertThat(error.status()).isEqualTo(403);
            assertThat(error.code()).isEqualTo(StompErrorMessage.FORBIDDEN_ROLE);
            assertThat(error.destination()).isEqualTo("/app/diagram/" + diagramId + "/mutate");
            assertThat(error.clientMutationId()).isEqualTo("mut-42");
        }

        @Test
        @DisplayName("SEND a /app/diagram/{id}/cursor: VIEWER sigue pudiendo")
        void sendCursorViewer() {
            when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                    .thenReturn(Optional.of(ProjectRole.VIEWER));

            Message<?> mensaje =
                    frame(StompCommand.SEND, "/app/diagram/" + diagramId + "/cursor", "{}", true);
            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "/app/project/%s/chat/extra",
                    "/app/project/%s/otra-cosa",
                    "/app/projects/%s/chat",
                })
        @DisplayName("SEND parecidos al del chat no se arbitran")
        void sendParecidosNoSeArbitran(String plantilla) {
            Message<?> mensaje =
                    frame(StompCommand.SEND, plantilla.formatted(projectId), "{}", true);

            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "/topic/diagrams/%s",
                    "/topic/diagrams/**",
                    "/topic/diagrams/*",
                    "/user/queue/errors",
                    "/topic/project/%s/chat",
                })
        @DisplayName("SUBSCRIBE que no cubren el chat pasan como antes (diagrams/** = hueco conocido)")
        void subscribeQueNoCubrenElChat(String plantilla) {
            Message<?> mensaje =
                    frame(StompCommand.SUBSCRIBE, plantilla.formatted(diagramId), "", true);

            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
        }

        @Test
        @DisplayName("SEND directo a /topic/diagrams/{id} sigue pasando sin control (riesgo pendiente)")
        void sendDirectoATopicDeDiagrama() {
            // Comportamiento actual, NO deseado: /topic es prefijo del broker y este SEND llega a
            // la sala sin pasar por el controlador. Fuera de alcance de CU19; documentado.
            Message<?> mensaje =
                    frame(StompCommand.SEND, "/topic/diagrams/" + diagramId, "{}", true);

            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
        }
    }

    // ================================================================= chat

    @Nested
    @DisplayName("Chat del proyecto (CU19)")
    class Chat {

        @ParameterizedTest(name = "un {0} puede enviar y suscribirse")
        @EnumSource(ProjectRole.class)
        @DisplayName("Cualquier rol, VIEWER incluido, envía y se suscribe")
        void cualquierRol(ProjectRole rol) {
            miembroCon(rol);

            Message<?> envio = frameChat("c-1");
            Message<?> suscripcion = frame(StompCommand.SUBSCRIBE, topicChat, "", true);

            assertThat(interceptor.preSend(envio, canal)).isSameAs(envio);
            assertThat(interceptor.preSend(suscripcion, canal)).isSameAs(suscripcion);
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            verify(projectMemberRepository, never()).findRoleByDiagramAndUser(any(), any());
        }

        @Test
        @DisplayName("No miembro: SEND descartado con NOT_PROJECT_MEMBER y su clientMessageId")
        void noMiembroEnvia() {
            noMiembro();

            assertThat(interceptor.preSend(frameChat("c-7"), canal)).isNull();

            StompErrorMessage error = errorEnviado();
            assertThat(error.status()).isEqualTo(403);
            assertThat(error.code()).isEqualTo("NOT_PROJECT_MEMBER");
            assertThat(error.destination()).isEqualTo(sendChat);
            assertThat(error.clientMutationId()).isEqualTo("c-7");
            assertThat(error.message()).isNotBlank();
        }

        @Test
        @DisplayName("No miembro: SUBSCRIBE descartado con NOT_PROJECT_MEMBER")
        void noMiembroSeSuscribe() {
            noMiembro();

            assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, topicChat, "", true), canal))
                    .isNull();

            StompErrorMessage error = errorEnviado();
            assertThat(error.status()).isEqualTo(403);
            assertThat(error.code()).isEqualTo("NOT_PROJECT_MEMBER");
            assertThat(error.destination()).isEqualTo(topicChat);
            assertThat(error.clientMutationId()).isNull();
        }

        @Test
        @DisplayName("projectId que no es UUID: INVALID_PAYLOAD 400, sin consultar la base")
        void projectIdNoUuid() {
            assertThat(interceptor.preSend(
                            frame(StompCommand.SEND, "/app/project/no-uuid/chat",
                                    "{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}", true),
                            canal))
                    .isNull();
            assertThat(interceptor.preSend(
                            frame(StompCommand.SUBSCRIBE, "/topic/projects/no-uuid/chat", "", true),
                            canal))
                    .isNull();

            ArgumentCaptor<Object> cuerpos = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate, times(2))
                    .convertAndSendToUser(eq(userId.toString()), eq("/queue/errors"), cuerpos.capture());
            assertThat(cuerpos.getAllValues())
                    .allSatisfy(
                            c -> {
                                StompErrorMessage e = (StompErrorMessage) c;
                                assertThat(e.status()).isEqualTo(400);
                                assertThat(e.code()).isEqualTo("INVALID_PAYLOAD");
                            });
            verifyNoInteractions(projectMemberRepository);
        }

        @Test
        @DisplayName("Sin Principal se descarta sin consultar ni avisar")
        void sinPrincipal() {
            assertThat(interceptor.preSend(frame(StompCommand.SEND, sendChat, "{}", false), canal))
                    .isNull();
            assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, topicChat, "", false), canal))
                    .isNull();
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
        }

        @Test
        @DisplayName("Miembro: el SUBSCRIBE exacto al topic del chat pasa")
        void subscribeExactoDeMiembro() {
            miembroCon(ProjectRole.EDITOR);

            Message<?> mensaje = frame(StompCommand.SUBSCRIBE, topicChat, "", true);
            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
        }

        @ParameterizedTest(name = "miembro={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("SEND directo a /topic/projects/{id}/chat: descartado con INVALID_PAYLOAD")
        void sendDirectoAlTopicDelChat(boolean esMiembro) {
            // La membresía no se llega a mirar: ni siquiera un miembro puede saltarse el servicio.
            // lenient: el stub existe para que el caso "miembro" sea real, aunque no se consulte.
            ProjectMember miembro = mock(ProjectMember.class);
            lenient().when(miembro.getRole()).thenReturn(ProjectRole.OWNER);
            lenient().when(projectMemberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                    .thenReturn(esMiembro ? Optional.of(miembro) : Optional.empty());

            Message<?> mensaje = frame(StompCommand.SEND, topicChat,
                    "{\"clientMessageId\":\"c-9\",\"text\":\"falso\"}", true);

            assertThat(interceptor.preSend(mensaje, canal)).isNull();

            StompErrorMessage error = errorEnviado();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_PAYLOAD");
            assertThat(error.destination()).isEqualTo(topicChat);
            assertThat(error.clientMutationId()).isEqualTo("c-9");
            verifyNoInteractions(projectMemberRepository);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "/topic/projects/*/chat",
                    "/topic/projects/**",
                    "/topic/**",
                    "/topic/*/**",
                    "/**",
                    "/topic/projects/{x}/chat",
                    "/topic/*/*/chat",
                    "/topic/projects/%s/chat/extra",
                    "/topic/projects/%s",
                    "/topic/projects/%s/ch?t",
                })
        @DisplayName("SUBSCRIBE con patrón o no exacto que cubriría el chat: INVALID_PAYLOAD")
        void subscribeConPatronSeRechaza(String plantilla) {
            String destino = plantilla.formatted(projectId);

            assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, destino, "", true), canal))
                    .isNull();

            StompErrorMessage error = errorEnviado();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_PAYLOAD");
            assertThat(error.destination()).isEqualTo(destino);
            verifyNoInteractions(projectMemberRepository);
        }

        @Test
        @DisplayName("Barra final en el topic del chat: no es el destino exacto, INVALID_PAYLOAD")
        void subscribeConBarraFinal() {
            String destino = topicChat + "/";

            assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, destino, "", true), canal))
                    .isNull();
            assertThat(errorEnviado().code()).isEqualTo("INVALID_PAYLOAD");
            verifyNoInteractions(projectMemberRepository);
        }

        @Test
        @DisplayName("Doble barra: el interceptor no la arbitra, pero el broker no le entrega el chat")
        void subscribeConDobleBarra() {
            String destino = "/topic//projects/" + projectId + "/chat";

            // No empieza por /topic/projects/ ni es patrón: pasa, como cualquier topic ajeno.
            Message<?> mensaje = frame(StompCommand.SUBSCRIBE, destino, "", true);
            assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
            verifyNoInteractions(projectMemberRepository, messagingTemplate);

            // Y no sirve para espiar: el registro del broker simple compara las suscripciones
            // sin patrón con String.equals, así que lo publicado en topicChat no le llega.
            DefaultSubscriptionRegistry registro = new DefaultSubscriptionRegistry();
            registro.registerSubscription(suscripcion("s-1", destino));
            registro.registerSubscription(suscripcion("s-2", "/topic/**"));

            assertThat(registro.findSubscriptions(publicacion(topicChat)))
                    .as("sólo el patrón (que el interceptor rechaza) casaría")
                    .containsOnlyKeys("sesion-s-2");
        }

        @Test
        @DisplayName("SUBSCRIBE con patrón sin sesión: descartado sin avisar")
        void subscribeConPatronSinPrincipal() {
            assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/**", "", false), canal))
                    .isNull();
            verifyNoInteractions(projectMemberRepository, messagingTemplate);
        }

        @Test
        @DisplayName("La membresía se consulta una vez por sesión, en role:project:{id}")
        void seCachea() {
            miembroCon(ProjectRole.VIEWER);

            interceptor.preSend(frame(StompCommand.SUBSCRIBE, topicChat, "", true), canal);
            interceptor.preSend(frameChat("c-1"), canal);
            interceptor.preSend(frameChat("c-2"), canal);

            verify(projectMemberRepository, times(1)).findByIdProjectIdAndIdUserId(projectId, userId);
            assertThat(atributosDeSesion.get("role:project:" + projectId))
                    .isEqualTo(Optional.of(ProjectRole.VIEWER));
        }

        @Test
        @DisplayName("La ausencia de membresía también se cachea")
        void laAusenciaSeCachea() {
            noMiembro();

            interceptor.preSend(frameChat("c-1"), canal);
            interceptor.preSend(frameChat("c-2"), canal);

            verify(projectMemberRepository, times(1)).findByIdProjectIdAndIdUserId(projectId, userId);
            assertThat(atributosDeSesion.get("role:project:" + projectId)).isEqualTo(Optional.empty());
        }
    }

    // ============================================================== soporte

    private static Message<?> suscripcion(String id, String destino) {
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        h.setSessionId("sesion-" + id);
        h.setSubscriptionId(id);
        h.setDestination(destino);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    private static Message<?> publicacion(String destino) {
        SimpMessageHeaderAccessor h = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        h.setDestination(destino);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    private void miembroCon(ProjectRole rol) {
        ProjectMember miembro = mock(ProjectMember.class);
        when(miembro.getRole()).thenReturn(rol);
        when(projectMemberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.of(miembro));
    }

    private void noMiembro() {
        when(projectMemberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.empty());
    }

    private StompErrorMessage errorEnviado() {
        ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSendToUser(eq(userId.toString()), eq("/queue/errors"), cuerpo.capture());
        assertThat(cuerpo.getValue()).isInstanceOf(StompErrorMessage.class);
        return (StompErrorMessage) cuerpo.getValue();
    }

    private Message<?> frameChat(String clientMessageId) {
        return frame(StompCommand.SEND, sendChat,
                "{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"hola\"}", true);
    }

    private Message<?> frameMutate(String clientMutationId) {
        return frame(StompCommand.SEND, "/app/diagram/" + diagramId + "/mutate",
                "{\"op\":\"MOVE_CLASS\",\"clientMutationId\":\"" + clientMutationId
                        + "\",\"payload\":{\"x\":10.0,\"y\":20.0}}",
                true);
    }

    private Message<?> frame(
            StompCommand comando, String destination, String cuerpo, boolean autenticado) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(comando);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(atributosDeSesion);
        if (autenticado) {
            accessor.setUser(new StompPrincipal(userId));
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(
                cuerpo.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }
}
