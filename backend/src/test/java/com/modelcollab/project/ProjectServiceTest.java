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
import com.modelcollab.project.dto.ProjectRequest;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import com.modelcollab.project.repository.ProjectRepository;
import com.modelcollab.project.service.ProjectService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Permisos sobre el proyecto y la regla de que un rol nunca baja. */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository memberRepository;
    @Mock private UserRepository userRepository;

    private ProjectService servicio;

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private User usuario;
    private Project proyecto;

    @BeforeEach
    void preparar() {
        servicio = new ProjectService(projectRepository, memberRepository, userRepository);
        usuario = new User("ana@modelcollab.dev", "$2a$10$hash", "Ana Pérez");
        ReflectionTestUtils.setField(usuario, "id", userId);
        proyecto = new Project("Barbería", null, usuario);
        ReflectionTestUtils.setField(proyecto, "id", projectId);
        lenient().when(projectRepository.findById(projectId)).thenReturn(Optional.of(proyecto));
    }

    private void conRol(ProjectRole rol) {
        when(memberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.of(new ProjectMember(proyecto, usuario, rol)));
    }

    // ------------------------------------------------------------- permisos

    @Test
    @DisplayName("Sin membresía se responde 403, no 404: no se filtra qué proyectos existen")
    void sinMembresiaEs403() {
        when(memberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.exigirMembresia(projectId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest(name = "un {0} {1} escribir")
    @CsvSource({"OWNER, puede", "EDITOR, puede", "VIEWER, no puede"})
    @DisplayName("Escribir en el proyecto lo pueden OWNER y EDITOR, no VIEWER")
    void quienPuedeEscribir(ProjectRole rol, String puede) {
        conRol(rol);
        if ("puede".equals(puede)) {
            assertThat(servicio.exigirEscritura(projectId, userId)).isEqualTo(rol);
        } else {
            assertThatThrownBy(() -> servicio.exigirEscritura(projectId, userId))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @ParameterizedTest(name = "un {0} {1} administrar")
    @CsvSource({"OWNER, puede", "EDITOR, no puede", "VIEWER, no puede"})
    @DisplayName("Compartir y agregar gente es sólo del dueño")
    void quienPuedeAdministrar(ProjectRole rol, String puede) {
        conRol(rol);
        if ("puede".equals(puede)) {
            servicio.exigirPropiedad(projectId, userId);
        } else {
            assertThatThrownBy(() -> servicio.exigirPropiedad(projectId, userId))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    // ------------------------------------------------------------- creación

    @Test
    @DisplayName("Crear un proyecto deja al creador como OWNER en la misma transacción")
    void crearDejaAlCreadorComoOwner() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario));
        when(projectRepository.save(any())).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", projectId);
            return p;
        });

        var respuesta = servicio.crear(userId, new ProjectRequest("Barbería", "desc"));

        assertThat(respuesta.role()).isEqualTo(ProjectRole.OWNER);
        assertThat(respuesta.ownerId()).isEqualTo(userId);
        // Sin la fila de membresía, el proyecto sería inalcanzable para su propio dueño: el
        // arbitraje de roles lo dejaría afuera y no habría forma de repararlo desde la app.
        var guardado = org.mockito.ArgumentCaptor.forClass(ProjectMember.class);
        verify(memberRepository).save(guardado.capture());
        assertThat(guardado.getValue().getRole()).isEqualTo(ProjectRole.OWNER);
    }

    // -------------------------------------------- un rol sube, nunca baja

    @ParameterizedTest(name = "quien es {0} y recibe {1} queda como {2}")
    @CsvSource({
        "VIEWER, EDITOR, EDITOR",
        "VIEWER, OWNER,  OWNER",
        "EDITOR, OWNER,  OWNER",
        "OWNER,  VIEWER, OWNER",
        "OWNER,  EDITOR, OWNER",
        "EDITOR, VIEWER, EDITOR",
        "EDITOR, EDITOR, EDITOR",
    })
    @DisplayName("Otorgar un rol sube o no hace nada, pero JAMÁS baja")
    void otorgarNuncaBaja(ProjectRole actual, ProjectRole otorgado, ProjectRole esperado) {
        ProjectMember miembro = new ProjectMember(proyecto, usuario, actual);
        when(memberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.of(miembro));

        assertThat(servicio.otorgar(projectId, usuario, otorgado).getRole()).isEqualTo(esperado);
        // Ya existía: no se inserta una fila nueva, se ajusta la que había.
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("El caso peligroso: el dueño abre su propio enlace de VIEWER y sigue siendo OWNER")
    void elDuenoNoSeDegradaASiMismo() {
        ProjectMember dueno = new ProjectMember(proyecto, usuario, ProjectRole.OWNER);
        when(memberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.of(dueno));

        servicio.otorgar(projectId, usuario, ProjectRole.VIEWER);

        // Si bajara, no habría vuelta atrás: para recuperar OWNER hay que ser OWNER.
        assertThat(dueno.getRole()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    @DisplayName("Otorgar es idempotente: la segunda vez no cambia nada")
    void otorgarEsIdempotente() {
        ProjectMember miembro = new ProjectMember(proyecto, usuario, ProjectRole.EDITOR);
        when(memberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.of(miembro));

        servicio.otorgar(projectId, usuario, ProjectRole.EDITOR);
        servicio.otorgar(projectId, usuario, ProjectRole.EDITOR);

        assertThat(miembro.getRole()).isEqualTo(ProjectRole.EDITOR);
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("A quien no era miembro se le crea la membresía")
    void alNuevoSeLeCrea() {
        when(memberRepository.findByIdProjectIdAndIdUserId(projectId, userId))
                .thenReturn(Optional.empty());
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(servicio.otorgar(projectId, usuario, ProjectRole.EDITOR).getRole())
                .isEqualTo(ProjectRole.EDITOR);
        verify(memberRepository).save(any());
    }

    @Test
    @DisplayName("Agregar por correo a alguien sin cuenta da 404, no crea nada")
    void correoSinCuenta() {
        conRol(ProjectRole.OWNER);
        when(userRepository.findByEmail("nadie@ejemplo.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.agregarMiembro(
                projectId, userId, "Nadie@Ejemplo.com ", ProjectRole.EDITOR))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(memberRepository, never()).save(any());
    }
}
