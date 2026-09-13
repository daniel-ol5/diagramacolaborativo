package com.modelcollab.xmi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.StompMutationMessage;
import com.modelcollab.collaboration.service.DiagramMutationService;
import com.modelcollab.diagram.dto.DiagramRequest;
import com.modelcollab.diagram.dto.DiagramResponse;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.DiagramService;
import com.modelcollab.diagram.service.PreGenerationValidatorService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.MetamodelValidator;
import com.modelcollab.project.repository.ProjectMemberRepository;
import com.modelcollab.project.service.ProjectService;
import com.modelcollab.xmi.service.XmiExporterService;
import com.modelcollab.xmi.service.XmiImporterService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** CU18: interoperabilidad XMI 2.1 con Enterprise Architect (§12, §14). */
@RestController
public class XmiController {

    private static final Logger log = LoggerFactory.getLogger(XmiController.class);

    private final XmiExporterService exportador;
    private final XmiImporterService importador;
    private final PreGenerationValidatorService validatorService;
    private final MetamodelValidator validator;
    private final DiagramMutationService mutationService;
    private final DiagramService diagramService;
    private final DiagramRepository diagramRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectService projectService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public XmiController(
            XmiExporterService exportador,
            XmiImporterService importador,
            PreGenerationValidatorService validatorService,
            MetamodelValidator validator,
            DiagramMutationService mutationService,
            DiagramService diagramService,
            DiagramRepository diagramRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectService projectService,
            SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper) {
        this.exportador = exportador;
        this.importador = importador;
        this.validatorService = validatorService;
        this.validator = validator;
        this.mutationService = mutationService;
        this.diagramService = diagramService;
        this.diagramRepository = diagramRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectService = projectService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Exporta el diagrama a XMI 2.1 para abrirlo en otra herramienta.
     *
     * <p>Lo puede pedir cualquier miembro, VIEWER incluido: exportar es leer.
     */
    @GetMapping("/api/v1/diagrams/{diagramId}/export-xmi")
    public ResponseEntity<byte[]> exportar(@PathVariable UUID diagramId, Principal emisor) {
        UUID userId = UUID.fromString(emisor.getName());
        if (projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No sos miembro del proyecto de este diagrama");
        }
        Diagram diagrama = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El diagrama no existe"));

        String xmi = exportador.exportar(
                validatorService.estadoDe(diagramId), diagrama.getName());
        String archivo = nombreDeArchivo(diagrama.getName());

        log.debug("XMI de {} exportado: {} bytes", diagramId, xmi.length());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + archivo + "\"")
                .body(xmi.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Importa un XMI como <b>diagrama nuevo</b> dentro del proyecto.
     *
     * <p>Crea uno nuevo en lugar de sobrescribir el actual a propósito: importar un archivo de
     * otra herramienta es traer un modelo, no reemplazar el trabajo de la sala. Si alguien quiere
     * sustituir un diagrama, borra el viejo — pero esa decisión es suya y no del importador.
     *
     * <p><b>Valida antes de aplicar.</b> Un XMI ajeno puede traer cosas que nuestro metamodelo no
     * acepta —herencia múltiple, por ejemplo, que Java no soporta— y aplicarlo dejaría un diagrama
     * en un estado que el generador no puede usar. Con errores bloqueantes responde 422 con el
     * mismo informe de CU13, y no crea nada.
     */
    @PostMapping("/api/v1/projects/{projectId}/import-xmi")
    public ResponseEntity<?> importar(
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile archivo,
            @RequestParam(required = false) String name,
            Principal emisor) {

        UUID userId = UUID.fromString(emisor.getName());
        // Importar CREA contenido, así que exige OWNER o EDITOR: no es una lectura.
        projectService.exigirEscritura(projectId, userId);

        if (archivo == null || archivo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se envió ningún archivo");
        }
        byte[] contenido;
        try {
            contenido = archivo.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo");
        }

        CanonicalModel modelo = importador.importar(contenido);

        ValidationReportDTO informe = validator.validar(modelo);
        if (!informe.valid()) {
            // Se rechaza ANTES de crear el diagrama: si se creara y después se informara el
            // problema, quedaría un diagrama inválido en el proyecto que alguien tiene que ir a
            // borrar a mano.
            log.debug("Importación a {} rechazada: {} error(es)", projectId, informe.errors().size());
            return ResponseEntity.unprocessableEntity().body(informe);
        }

        String nombre = name == null || name.isBlank()
                ? nombreDesdeElArchivo(archivo.getOriginalFilename())
                : name;
        DiagramResponse creado =
                diagramService.crear(projectId, userId, new DiagramRequest(nombre));

        // El contenido entra como un BULK_MERGE: una sola transacción, un solo número de
        // secuencia y un ÚNICO evento a la sala (§8.1). Reemitirlo clase por clase haría
        // parpadear N veces el lienzo de cada colaborador.
        var resultado = mutationService.aplicar(creado.id(), userId, new StompMutationMessage(
                OperationType.BULK_MERGE.name(),
                null,
                "xmi-import-" + creado.id(),
                objectMapper.convertValue(modelo, new com.fasterxml.jackson.core.type.TypeReference<
                        Map<String, Object>>() {})));

        if (resultado instanceof DiagramMutationService.Resultado.Aplicada aplicada) {
            messagingTemplate.convertAndSend(
                    "/topic/diagrams/" + creado.id(), aplicada.difusion());
        }
        log.debug("XMI importado al proyecto {} como diagrama {}", projectId, creado.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(creado);
    }

    private static String nombreDeArchivo(String nombreDelDiagrama) {
        String limpio = (nombreDelDiagrama == null ? "diagrama" : nombreDelDiagrama)
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return (limpio.isBlank() ? "diagrama" : limpio) + ".xmi";
    }

    private static String nombreDesdeElArchivo(String nombreOriginal) {
        if (nombreOriginal == null || nombreOriginal.isBlank()) {
            return "Diagrama importado";
        }
        int punto = nombreOriginal.lastIndexOf('.');
        String sinExtension = punto > 0 ? nombreOriginal.substring(0, punto) : nombreOriginal;
        return sinExtension.isBlank() ? "Diagrama importado" : sinExtension;
    }
}
