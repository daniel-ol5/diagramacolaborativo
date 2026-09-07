package com.modelcollab.project.service;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.project.dto.MemberResponse;
import com.modelcollab.project.dto.ProjectRequest;
import com.modelcollab.project.dto.ProjectResponse;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectMember;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import com.modelcollab.project.repository.ProjectRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proyectos y membresías.
 *
 * <p>Además de crear y listar, es <b>el guardián de los permisos sobre un proyecto</b>: los
 * controladores no consultan roles por su cuenta, se los piden acá. Tener la comprobación en un
 * solo lugar es lo que evita que un endpoint nuevo se olvide de hacerla — que es exactamente
 * cómo aparece un agujero de autorización.
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final UserRepository userRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectMemberRepository memberRepository,
            UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------- permisos

    /**
     * Devuelve mi rol en el proyecto, o 403 si no soy miembro.
     *
     * <p>No distingue "el proyecto no existe" de "no sos miembro", y es a propósito: si
     * respondiera 404 en un caso y 403 en el otro, cualquiera podría averiguar qué proyectos
     * existen probando identificadores.
     */
    @Transactional(readOnly = true)
    public ProjectRole exigirMembresia(UUID projectId, UUID userId) {
        return memberRepository.findByIdProjectIdAndIdUserId(projectId, userId)
                .map(ProjectMember::getRole)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
    }

    /** Para crear o modificar cosas del proyecto: OWNER o EDITOR. */
    @Transactional(readOnly = true)
    public ProjectRole exigirEscritura(UUID projectId, UUID userId) {
        ProjectRole rol = exigirMembresia(projectId, userId);
        if (rol == ProjectRole.VIEWER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tu rol de VIEWER no permite modificar este proyecto");
        }
        return rol;
    }

    /** Para administrar el proyecto —compartir, agregar gente—: sólo OWNER. */
    @Transactional(readOnly = true)
    public void exigirPropiedad(UUID projectId, UUID userId) {
        if (exigirMembresia(projectId, userId) != ProjectRole.OWNER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Sólo el dueño del proyecto puede hacer esto");
        }
    }

    // ------------------------------------------------------------ proyectos

    @Transactional(readOnly = true)
    public List<ProjectResponse> misProyectos(UUID userId) {
        return memberRepository.findMembresiasDelUsuario(userId).stream()
                .map(pm -> ProjectResponse.de(pm.getProject(), pm.getRole()))
                .sorted(Comparator.comparing(
                        ProjectResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Crea el proyecto y <b>al creador como OWNER en la misma transacción</b>.
     *
     * <p>Que sean dos escrituras y una sola transacción importa: un proyecto sin fila en
     * {@code project_members} sería inalcanzable para su propio dueño —el arbitraje de roles lo
     * dejaría afuera— y no habría forma de repararlo desde la aplicación.
     */
    @Transactional
    public ProjectResponse crear(UUID userId, ProjectRequest peticion) {
        User dueno = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "El usuario del token ya no existe"));

        Project proyecto = projectRepository.save(
                new Project(peticion.name(), peticion.description(), dueno));
        memberRepository.save(new ProjectMember(proyecto, dueno, ProjectRole.OWNER));

        return ProjectResponse.de(proyecto, ProjectRole.OWNER);
    }

    // ------------------------------------------------------------- miembros

    @Transactional(readOnly = true)
    public List<MemberResponse> miembros(UUID projectId, UUID userId) {
        exigirMembresia(projectId, userId);
        return memberRepository.findMiembrosConUsuario(projectId).stream()
                .map(MemberResponse::de)
                .sorted(Comparator.comparing(MemberResponse::role)
                        .thenComparing(MemberResponse::fullName))
                .toList();
    }

    /**
     * Agrega a alguien por correo. Sólo el dueño.
     *
     * <p>No manda ningún correo: hacerlo de verdad exige un servidor de correo y no le agrega
     * nada a lo que hay que demostrar. Si la persona no está registrada, se responde 404 y el
     * dueño le pasa un enlace de invitación, que es el camino pensado para ese caso.
     */
    @Transactional
    public MemberResponse agregarMiembro(
            UUID projectId, UUID solicitante, String email, ProjectRole rol) {

        exigirPropiedad(projectId, solicitante);

        User invitado = userRepository
                .findByEmail(email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No hay ningún usuario registrado con ese correo. "
                                + "Compartile un enlace de invitación."));

        return MemberResponse.de(otorgar(projectId, invitado, rol));
    }

    /**
     * Otorga un rol, <b>sin bajar nunca uno existente</b>.
     *
     * <p>Es la regla que impide el error clásico: el dueño prueba su propio enlace de VIEWER y se
     * degrada a sí mismo — sin poder recuperarse, porque para revertirlo haría falta ser OWNER.
     * El rol sube o se queda como está; jamás baja. También la hace idempotente: otorgar dos
     * veces lo mismo no cambia nada.
     */
    @Transactional
    public ProjectMember otorgar(UUID projectId, User usuario, ProjectRole rol) {
        Project proyecto = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El proyecto no existe"));

        Optional<ProjectMember> existente =
                memberRepository.findByIdProjectIdAndIdUserId(projectId, usuario.getId());

        if (existente.isPresent()) {
            ProjectMember miembro = existente.get();
            if (jerarquia(rol) > jerarquia(miembro.getRole())) {
                miembro.setRole(rol);
            }
            return miembro;
        }
        return memberRepository.save(new ProjectMember(proyecto, usuario, rol));
    }

    /** OWNER manda sobre EDITOR, que manda sobre VIEWER. */
    private static int jerarquia(ProjectRole rol) {
        return switch (rol) {
            case OWNER -> 3;
            case EDITOR -> 2;
            case VIEWER -> 1;
        };
    }
}
