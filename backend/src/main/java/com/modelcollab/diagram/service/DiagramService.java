package com.modelcollab.diagram.service;

import com.modelcollab.diagram.dto.DiagramRequest;
import com.modelcollab.diagram.dto.DiagramResponse;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.repository.ProjectRepository;
import com.modelcollab.project.service.ProjectService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Alta y listado de diagramas dentro de un proyecto (§14). */
@Service
public class DiagramService {

    private final DiagramRepository diagramRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;

    public DiagramService(
            DiagramRepository diagramRepository,
            ProjectRepository projectRepository,
            ProjectService projectService) {
        this.diagramRepository = diagramRepository;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
    }

    @Transactional(readOnly = true)
    public List<DiagramResponse> listar(UUID projectId, UUID userId) {
        // Un VIEWER puede listar: mirar es lo que sí puede hacer.
        projectService.exigirMembresia(projectId, userId);
        return diagramRepository.findByProjectId(projectId).stream()
                .map(DiagramResponse::de)
                .sorted(Comparator.comparing(
                        DiagramResponse::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Crea un diagrama vacío. Requiere OWNER o EDITOR.
     *
     * <p>El estado inicial sale de {@code Diagram.ESTADO_INICIAL}, que es el mismo literal que el
     * DEFAULT de {@code V1__init.sql}. Que sea el mismo importa: si un diagrama recién creado no
     * deserializara al mismo canónico que el lienzo espera, todo lo construido del Día 6 en
     * adelante se rompería en el caso más común de todos —abrir un diagrama nuevo— y no en un
     * borde raro.
     */
    @Transactional
    public DiagramResponse crear(UUID projectId, UUID userId, DiagramRequest peticion) {
        projectService.exigirEscritura(projectId, userId);

        Project proyecto = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El proyecto no existe"));

        return DiagramResponse.de(diagramRepository.save(new Diagram(proyecto, peticion.name())));
    }
}
