package com.modelcollab.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.project.dto.InvitationPreview;
import com.modelcollab.project.dto.InvitationRequest;
import com.modelcollab.project.dto.InvitationResponse;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectInvitation;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectInvitationRepository;
import com.modelcollab.project.repository.ProjectRepository;
import com.modelcollab.project.service.InvitationService;
import com.modelcollab.project.service.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Enlaces de invitación: generación, vigencia, revocación y aceptación. */
@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock private ProjectInvitationRepository invitationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProjectService projectService;

    /** Reloj que sólo avanza cuando el test se lo pide. */
    private static final class RelojDePrueba extends Clock {
        private Instant ahora = Instant.parse("2026-09-07T12:00:00Z");

        void avanzarDias(int dias) {
            ahora = ahora.plusSeconds(dias * 86400L);
        }

        @Override public Instant instant() { return ahora; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
    }

    private RelojDePrueba reloj;
    private InvitationService servicio;

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private User usuario;
    private Project proyecto;

    @BeforeEach
    void preparar() {
        reloj = new RelojDePrueba();
        servicio = new InvitationService(invitationRepository, projectRepository,
                userRepository, projectService, "https://modelcollab.dev", reloj);

        usuario = new User("ana@modelcollab.dev", "$2a$10$hash", "Ana Pérez");
        ReflectionTestUtils.setField(usuario, "id", userId);
        proyecto = new Project("Barbería Central", null, usuario);
        ReflectionTestUtils.setField(proyecto, "id", projectId);

        lenient().when(projectRepository.findById(projectId)).thenReturn(Optional.of(proyecto));
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(usuario));
        lenient().when(invitationRepository.save(any())).thenAnswer(inv -> {
            ProjectInvitation i = inv.getArgument(0);
            if (i.getId() == null) {
                ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
            }
            ReflectionTestUtils.setField(i, "createdAt", OffsetDateTime.now(reloj));
            return i;
        });
    }

    private ProjectInvitation invitacionVigente(ProjectRole rol) {
        ProjectInvitation i = new ProjectInvitation(proyecto, "tok-vigente", rol, usuario, null);
        ReflectionTestUtils.setField(i, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(i, "createdAt", OffsetDateTime.now(reloj));
        return i;
    }

    // ------------------------------------------------------------------ token

    @Test
    @DisplayName("El token es aleatorio, largo y seguro para una URL")
    void tokenSeguro() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            InvitationResponse r = servicio.crear(
                    projectId, userId, new InvitationRequest(ProjectRole.EDITOR, null));
            vistos.add(r.token());
            // base64url sin relleno: viaja en una URL sin necesitar escapado.
            assertThat(r.token()).matches("[A-Za-z0-9_-]{43}");
        }
        // Doscientos tokens distintos: no es secuencial ni derivado del id del proyecto, que es
        // lo que convertiría el enlace en algo adivinable.
        assertThat(vistos).hasSize(200);
        assertThat(vistos).noneMatch(t -> t.contains(projectId.toString()));
    }

    @Test
    @DisplayName("La respuesta trae la URL ya armada")
    void urlArmada() {
        InvitationResponse r = servicio.crear(
                projectId, userId, new InvitationRequest(ProjectRole.VIEWER, 7));

        assertThat(r.url()).isEqualTo("https://modelcollab.dev/invite/" + r.token());
        assertThat(r.role()).isEqualTo(ProjectRole.VIEWER);
        assertThat(r.active()).isTrue();
        assertThat(r.expiresAt()).isEqualTo(OffsetDateTime.now(reloj).plusDays(7));
    }

    @Test
    @DisplayName("Sin expiresInDays el enlace no vence")
    void sinVencimiento() {
        assertThat(servicio.crear(projectId, userId,
                new InvitationRequest(ProjectRole.EDITOR, null)).expiresAt()).isNull();
    }

    @Test
    @DisplayName("Sólo el dueño genera enlaces")
    void soloElDuenoGenera() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(projectService).exigirPropiedad(projectId, userId);

        assertThatThrownBy(() -> servicio.crear(
                projectId, userId, new InvitationRequest(ProjectRole.OWNER, null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(invitationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ mirar

    @Test
    @DisplayName("Mirar un enlace válido dice el proyecto y el rol, sin agregar a nadie")
    void mirarValido() {
        when(invitationRepository.findPorToken("tok-vigente"))
                .thenReturn(Optional.of(invitacionVigente(ProjectRole.EDITOR)));

        InvitationPreview vista = servicio.mirar("tok-vigente");

        assertThat(vista.valid()).isTrue();
        assertThat(vista.projectName()).isEqualTo("Barbería Central");
        assertThat(vista.role()).isEqualTo(ProjectRole.EDITOR);
        // Mirar no da acceso: aceptar tiene que ser un acto deliberado.
        verify(projectService, never()).otorgar(any(), any(), any());
    }

    @Test
    @DisplayName("Un token inexistente NO revela el nombre de ningún proyecto")
    void tokenInexistenteNoFiltra() {
        when(invitationRepository.findPorToken("inventado")).thenReturn(Optional.empty());

        InvitationPreview vista = servicio.mirar("inventado");

        assertThat(vista.valid()).isFalse();
        assertThat(vista.reason()).isEqualTo("NOT_FOUND");
        // Si dijera el nombre, probar tokens al azar sería una forma de descubrir qué proyectos
        // existen.
        assertThat(vista.projectName()).isNull();
        assertThat(vista.role()).isNull();
    }

    @Test
    @DisplayName("Vencido y revocado se distinguen entre sí, pero tampoco revelan el proyecto")
    void vencidoYRevocadoSeDistinguen() {
        ProjectInvitation vencida =
                new ProjectInvitation(proyecto, "tok-vencido", ProjectRole.EDITOR, usuario,
                        OffsetDateTime.now(reloj).plusDays(1));
        when(invitationRepository.findPorToken("tok-vencido")).thenReturn(Optional.of(vencida));
        reloj.avanzarDias(2);

        InvitationPreview v = servicio.mirar("tok-vencido");
        assertThat(v.valid()).isFalse();
        assertThat(v.reason()).isEqualTo("EXPIRED");
        assertThat(v.projectName()).isNull();

        ProjectInvitation revocada = invitacionVigente(ProjectRole.EDITOR);
        revocada.revocar(OffsetDateTime.now(reloj));
        when(invitationRepository.findPorToken("tok-revocado")).thenReturn(Optional.of(revocada));

        InvitationPreview r = servicio.mirar("tok-revocado");
        assertThat(r.reason()).isEqualTo("REVOKED");
        assertThat(r.projectName()).isNull();
    }

    // ---------------------------------------------------------------- aceptar

    @Test
    @DisplayName("Aceptar delega en la regla que nunca baja un rol")
    void aceptarDelegaEnOtorgar() {
        ProjectInvitation invitacion = invitacionVigente(ProjectRole.EDITOR);
        when(invitationRepository.findPorToken("tok-vigente"))
                .thenReturn(Optional.of(invitacion));

        assertThat(servicio.aceptar("tok-vigente", userId)).isEqualTo(projectId);

        // La regla vive en ProjectService.otorgar, así que vale igual por este camino que
        // agregando a alguien por correo. Repetirla acá sería tener dos versiones que divergen.
        verify(projectService).otorgar(projectId, usuario, ProjectRole.EDITOR);
    }

    @Test
    @DisplayName("Un enlace revocado ya no se puede aceptar")
    void revocadoNoSeAcepta() {
        ProjectInvitation invitacion = invitacionVigente(ProjectRole.EDITOR);
        invitacion.revocar(OffsetDateTime.now(reloj));
        when(invitationRepository.findPorToken("tok-vigente"))
                .thenReturn(Optional.of(invitacion));

        assertThatThrownBy(() -> servicio.aceptar("tok-vigente", userId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        verify(projectService, never()).otorgar(any(), any(), any());
    }

    @Test
    @DisplayName("Un enlace vencido ya no se puede aceptar")
    void vencidoNoSeAcepta() {
        ProjectInvitation invitacion = new ProjectInvitation(
                proyecto, "tok", ProjectRole.EDITOR, usuario,
                OffsetDateTime.now(reloj).plusDays(3));
        when(invitationRepository.findPorToken("tok")).thenReturn(Optional.of(invitacion));

        reloj.avanzarDias(4);

        assertThatThrownBy(() -> servicio.aceptar("tok", userId))
                .isInstanceOf(ResponseStatusException.class);
        verify(projectService, never()).otorgar(any(), any(), any());
    }

    // ---------------------------------------------------------------- revocar

    @Test
    @DisplayName("Revocar marca la fila en vez de borrarla")
    void revocarMarca() {
        ProjectInvitation invitacion = invitacionVigente(ProjectRole.EDITOR);
        when(invitationRepository.findById(invitacion.getId()))
                .thenReturn(Optional.of(invitacion));

        servicio.revocar(projectId, invitacion.getId(), userId);

        // Queda el rastro de que existió, quién la creó y cuándo se cortó — que es justo lo que
        // se quiere mirar cuando hubo una filtración.
        assertThat(invitacion.getRevokedAt()).isNotNull();
        verify(invitationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("No se puede revocar una invitación de otro proyecto conociendo su id")
    void noSeRevocaLaDeOtroProyecto() {
        Project ajeno = new Project("Ajeno", null, usuario);
        ReflectionTestUtils.setField(ajeno, "id", UUID.randomUUID());
        ProjectInvitation deOtro =
                new ProjectInvitation(ajeno, "tok", ProjectRole.OWNER, usuario, null);
        UUID idDeOtro = UUID.randomUUID();
        ReflectionTestUtils.setField(deOtro, "id", idDeOtro);
        when(invitationRepository.findById(idDeOtro)).thenReturn(Optional.of(deOtro));

        // La ruta lleva mi projectId, pero el id de la invitación podría ser de cualquiera.
        assertThatThrownBy(() -> servicio.revocar(projectId, idDeOtro, userId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deOtro.getRevokedAt()).isNull();
    }
}
