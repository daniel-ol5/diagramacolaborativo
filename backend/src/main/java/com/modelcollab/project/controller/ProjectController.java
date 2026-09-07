package com.modelcollab.project.controller;

import com.modelcollab.diagram.dto.DiagramRequest;
import com.modelcollab.diagram.dto.DiagramResponse;
import com.modelcollab.diagram.service.DiagramService;
import com.modelcollab.project.dto.MemberRequest;
import com.modelcollab.project.dto.MemberResponse;
import com.modelcollab.project.dto.ProjectRequest;
import com.modelcollab.project.dto.ProjectResponse;
import com.modelcollab.project.service.ProjectService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proyectos, sus diagramas y sus miembros (§14).
 *
 * <p>Los diagramas anidados viven acá y no en {@code DiagramController} porque las rutas cuelgan
 * de {@code /projects}: un controlador por prefijo de URL evita mapeos ambiguos. Lo que se hace
 * <b>sobre</b> un diagrama ya existente —snapshot, sync-offline— sigue en su propio controlador.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final DiagramService diagramService;

    public ProjectController(ProjectService projectService, DiagramService diagramService) {
        this.projectService = projectService;
        this.diagramService = diagramService;
    }

    /** Los proyectos donde soy miembro, cada uno con mi rol. */
    @GetMapping
    public List<ProjectResponse> misProyectos(Principal emisor) {
        return projectService.misProyectos(userId(emisor));
    }

    /** Crea el proyecto y me deja como OWNER. */
    @PostMapping
    public ResponseEntity<ProjectResponse> crear(
            @Valid @RequestBody ProjectRequest peticion, Principal emisor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.crear(userId(emisor), peticion));
    }

    @GetMapping("/{projectId}/members")
    public List<MemberResponse> miembros(@PathVariable UUID projectId, Principal emisor) {
        return projectService.miembros(projectId, userId(emisor));
    }

    /** Agrega a alguien ya registrado. Sólo el dueño; 404 si ese correo no tiene cuenta. */
    @PostMapping("/{projectId}/members")
    public ResponseEntity<MemberResponse> agregarMiembro(
            @PathVariable UUID projectId,
            @Valid @RequestBody MemberRequest peticion,
            Principal emisor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.agregarMiembro(
                projectId, userId(emisor), peticion.email(), peticion.role()));
    }

    @GetMapping("/{projectId}/diagrams")
    public List<DiagramResponse> diagramas(@PathVariable UUID projectId, Principal emisor) {
        return diagramService.listar(projectId, userId(emisor));
    }

    @PostMapping("/{projectId}/diagrams")
    public ResponseEntity<DiagramResponse> crearDiagrama(
            @PathVariable UUID projectId,
            @Valid @RequestBody DiagramRequest peticion,
            Principal emisor) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(diagramService.crear(projectId, userId(emisor), peticion));
    }

    private static UUID userId(Principal emisor) {
        return UUID.fromString(emisor.getName());
    }
}
