package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.collaboration.interceptor.StompChannelInterceptor;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * <b>Criterio de aceptación del Día 2: un VIEWER recibe 403 al intentar mutar por STOMP.</b>
 *
 * <p>Test de unidad con repositorios mockeados, sin {@code @SpringBootTest} y sin base de datos:
 * no hay Docker en el equipo de desarrollo (desvío D-01), así que Testcontainers no es una
 * opción y levantar el contexto exigiría un PostgreSQL vivo para que Flyway y la validación de
 * esquema pasen. Lo que se verifica acá es la lógica de arbitraje, que es donde está el riesgo.
 */
@ExtendWith(MockitoExtension.class)
class StompChannelInterceptorTest {

    private static final String TOKEN_VALIDO = "jwt-valido";

    @Mock private com.modelcollab.auth.service.JwtService jwtService;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private MessageChannel canal;

    private StompChannelInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();
    private final String destino = "/app/diagram/" + diagramId + "/mutate";

    /** Compartido entre frames a propósito: en una sesión STOMP real es el mismo mapa. */
    private final Map<String, Object> atributosDeSesion = new HashMap<>();

    @BeforeEach
    void prepararInterceptor() {
        interceptor =
                new StompChannelInterceptor(
                        jwtService,
                        projectMemberRepository,
                        messagingTemplate,
                        JacksonConfig.canonicalObjectMapper());
    }

    // ------------------------------------------------------------- CONNECT

    @Test
    @DisplayName("CONNECT con un JWT válido queda autenticado con el userId como Principal")
    void connectValido() {
        when(jwtService.parse(TOKEN_VALIDO)).thenReturn(userId);

        Message<?> mensaje = frameConnect("Bearer " + TOKEN_VALIDO);
        Message<?> resultado = interceptor.preSend(mensaje, canal);

        assertThat(resultado).isNotNull();
        Principal principal = StompHeaderAccessor.wrap(resultado).getUser();
        assertThat(principal).isInstanceOf(StompPrincipal.class);
        // getName() debe ser el userId en texto: es lo que resuelve los destinos /user/**.
        assertThat(principal.getName()).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("CONNECT sin header Authorization se rechaza y la conexión no llega a existir")
    void connectSinToken() {
        Message<?> mensaje = frameConnect(null);

        assertThatThrownBy(() -> interceptor.preSend(mensaje, canal))
                .isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT con un token inválido se rechaza")
    void connectTokenInvalido() {
        when(jwtService.parse("basura")).thenThrow(new JwtException("firma inválida"));

        Message<?> mensaje = frameConnect("Bearer basura");

        assertThatThrownBy(() -> interceptor.preSend(mensaje, canal))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // ---------------------------------------------------------------- SEND

    @ParameterizedTest(name = "un {0} puede mutar")
    @EnumSource(value = ProjectRole.class, names = {"OWNER", "EDITOR"})
    @DisplayName("OWNER y EDITOR pasan: el mensaje sigue su curso intacto")
    void rolesQueMutan(ProjectRole rol) {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(rol));

        Message<?> mensaje = frameMutate("mut-1");
        assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("CRITERIO DEL DÍA: un VIEWER es descartado y recibe 403 por su canal privado")
    void viewerRecibe403() {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(ProjectRole.VIEWER));

        Message<?> resultado = interceptor.preSend(frameMutate("mut-42"), canal);

        // null = el mensaje se descarta y NO llega al controlador...
        assertThat(resultado).as("la mutación de un VIEWER no debe propagarse").isNull();

        // ...pero la sesión sobrevive: el error va por /user/queue/errors, no como frame ERROR.
        // Si esto se hiciera lanzando, STOMP cerraría la conexión y el VIEWER dejaría de ver
        // los cambios de los demás por haber tocado un nodo sin querer.
        StompErrorMessage error = errorEnviado();
        assertThat(error.status()).isEqualTo(403);
        assertThat(error.code()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(error.destination()).isEqualTo(destino);
        assertThat(error.clientMutationId())
                .as("el cliente necesita saber cuál de sus mutaciones fue rechazada")
                .isEqualTo("mut-42");
        assertThat(error.message()).isNotBlank();
    }

    @Test
    @DisplayName("Sin membresía en el proyecto también se descarta con 403")
    void sinMembresia() {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.empty());

        assertThat(interceptor.preSend(frameMutate("mut-7"), canal)).isNull();
        assertThat(errorEnviado().status()).isEqualTo(403);
    }

    @Test
    @DisplayName("El error se dirige al solicitante, no a la sala")
    void elErrorEsPrivado() {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(ProjectRole.VIEWER));

        interceptor.preSend(frameMutate("mut-1"), canal);

        verify(messagingTemplate)
                .convertAndSendToUser(
                        eq(userId.toString()), eq("/queue/errors"), any(Object.class));
    }

    @Test
    @DisplayName("Un cuerpo sin clientMutationId igual produce el 403, con el campo nulo")
    void cuerpoSinClientMutationId() {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(ProjectRole.VIEWER));

        assertThat(interceptor.preSend(frameMutate(null), canal)).isNull();
        assertThat(errorEnviado().clientMutationId()).isNull();
    }

    // --------------------------------------------------------------- caché

    @Test
    @DisplayName("El rol se consulta una sola vez por sesión, no una vez por mensaje")
    void elRolSeCachea() {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.of(ProjectRole.EDITOR));

        // Un arrastre real manda ~60 MOVE_CLASS por segundo; acá alcanzan tres para demostrar
        // que la segunda y la tercera no vuelven a la base.
        interceptor.preSend(frameMutate("mut-1"), canal);
        interceptor.preSend(frameMutate("mut-2"), canal);
        interceptor.preSend(frameMutate("mut-3"), canal);

        verify(projectMemberRepository, times(1)).findRoleByDiagramAndUser(diagramId, userId);
    }

    @Test
    @DisplayName("La ausencia de membresía también se cachea: un no-miembro no martilla la base")
    void laAusenciaTambienSeCachea() {
        when(projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId))
                .thenReturn(Optional.empty());

        interceptor.preSend(frameMutate("mut-1"), canal);
        interceptor.preSend(frameMutate("mut-2"), canal);

        verify(projectMemberRepository, times(1)).findRoleByDiagramAndUser(diagramId, userId);
        verify(messagingTemplate, times(2)).convertAndSendToUser(any(), any(), any(Object.class));
    }

    // ------------------------------------------------------- otros destinos

    @Test
    @DisplayName("Un SEND que no es de diagrama no lo arbitra esta regla")
    void destinoAjeno() {
        Message<?> mensaje = frame(StompCommand.SEND, "/app/presence/ping", "{}", true);

        assertThat(interceptor.preSend(mensaje, canal)).isSameAs(mensaje);
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    @DisplayName("Un SEND a un diagrama sin sesión autenticada se descarta")
    void sendSinPrincipal() {
        Message<?> mensaje = frame(StompCommand.SEND, destino, "{}", false);

        assertThat(interceptor.preSend(mensaje, canal)).isNull();
        verifyNoInteractions(projectMemberRepository);
    }

    // ------------------------------------------------------------- soporte

    private StompErrorMessage errorEnviado() {
        ArgumentCaptor<Object> cuerpo = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSendToUser(eq(userId.toString()), eq("/queue/errors"), cuerpo.capture());
        assertThat(cuerpo.getValue()).isInstanceOf(StompErrorMessage.class);
        return (StompErrorMessage) cuerpo.getValue();
    }

    private Message<?> frameConnect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> frameMutate(String clientMutationId) {
        String cuerpo =
                clientMutationId == null
                        ? "{\"op\":\"MOVE_CLASS\",\"payload\":{\"x\":10.0,\"y\":20.0}}"
                        : "{\"op\":\"MOVE_CLASS\",\"clientMutationId\":\""
                                + clientMutationId
                                + "\",\"payload\":{\"x\":10.0,\"y\":20.0}}";
        return frame(StompCommand.SEND, destino, cuerpo, true);
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
