package com.modelcollab.diagram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.diagram.dto.DiagramSnapshotResponse;
import com.modelcollab.diagram.dto.OfflineSyncBroadcast;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.ReconciliationReport;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.OfflineReconciliationService;
import com.modelcollab.diagram.service.PreGenerationValidatorService;
import com.modelcollab.generator.service.SpringBootGeneratorService;
import com.modelcollab.generator.service.ZipPackagingService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Endpoints REST del diagrama (§14): snapshot, rebase offline, validación y generación. */
@RestController
@RequestMapping("/api/v1/diagrams")
public class DiagramController {

    private static final Logger log = LoggerFactory.getLogger(DiagramController.class);

    private final OfflineReconciliationService reconciliationService;
    private final PreGenerationValidatorService validatorService;
    private final SpringBootGeneratorService generatorService;
    private final ZipPackagingService zipService;
    private final IdentifierSanitizer sanitizer;
    private final DiagramRepository diagramRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public DiagramController(
            OfflineReconciliationService reconciliationService,
            PreGenerationValidatorService validatorService,
            SpringBootGeneratorService generatorService,
            ZipPackagingService zipService,
            IdentifierSanitizer sanitizer,
            DiagramRepository diagramRepository,
            ProjectMemberRepository projectMemberRepository,
            SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper) {
        this.reconciliationService = reconciliationService;
        this.validatorService = validatorService;
        this.generatorService = generatorService;
        this.zipService = zipService;
        this.sanitizer = sanitizer;
        this.diagramRepository = diagramRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Estado canónico actual del diagrama (§14).
     *
     * <p>Lo lee <b>cualquier miembro del proyecto, VIEWER incluido</b>: mirar es precisamente lo
     * que un VIEWER sí puede hacer. Quien no es miembro no.
     */
    @GetMapping("/{diagramId}/snapshot")
    public DiagramSnapshotResponse snapshot(@PathVariable UUID diagramId, Principal emisor) {
        exigirMembresia(diagramId, userId(emisor));
        Diagram diagrama = traer(diagramId);
        try {
            return new DiagramSnapshotResponse(
                    diagrama.getId(),
                    diagrama.getName(),
                    diagrama.getMutationVersion(),
                    objectMapper.readValue(diagrama.getCurrentState(), CanonicalModel.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "El current_state almacenado no es un modelo canónico válido", e);
        }
    }

    // -------------------------------------------------- CU13: validar diagrama

    /**
     * Informe de validación pre-generación (§11.2.6).
     *
     * <p>Lo puede pedir <b>cualquier miembro, VIEWER incluido</b>: validar es leer. Y es útil
     * poder consultarlo sin intención de generar, para ir corrigiendo el diagrama mientras se
     * modela en vez de descubrir los problemas recién al pedir el ZIP.
     */
    @GetMapping("/{diagramId}/validate")
    public ValidationReportDTO validar(@PathVariable UUID diagramId, Principal emisor) {
        exigirMembresia(diagramId, userId(emisor));
        return validatorService.validar(diagramId);
    }

    // --------------------------------------------- CU14: generar backend (ZIP)

    /**
     * Genera el proyecto Spring Boot completo y lo devuelve como ZIP.
     *
     * <p><b>La validación corre primero y puede impedir la descarga.</b> Con errores bloqueantes
     * se responde <b>422</b> con el informe y no se genera nada: entregar un ZIP que no compila es
     * peor que no entregarlo, porque el usuario lo descubre después de descomprimir y sin ninguna
     * pista de qué estaba mal en su diagrama. Las advertencias, en cambio, no impiden nada —
     * describen decisiones que el generador toma solo.
     *
     * <p>Lo puede pedir cualquier miembro, <b>VIEWER incluido</b>: generar no modifica el
     * diagrama, es una lectura que produce un archivo. Prohibírselo no protegería nada.
     */
    @PostMapping("/{diagramId}/generate-backend")
    public ResponseEntity<?> generarBackend(
            @PathVariable UUID diagramId,
            @RequestParam(defaultValue = "com.empresa.app") String basePackage,
            Principal emisor) {

        exigirMembresia(diagramId, userId(emisor));

        ValidationReportDTO informe = validatorService.validar(diagramId);
        if (!informe.valid()) {
            log.debug("Generación de {} rechazada: {} error(es) bloqueante(s)",
                    diagramId, informe.errors().size());
            return ResponseEntity.unprocessableEntity().body(informe);
        }

        String artefacto = artefactoDe(traer(diagramId).getName());
        byte[] zip = zipService.empaquetar(generatorService.generar(
                validatorService.estadoDe(diagramId), basePackage, artefacto));

        log.debug("ZIP de {} generado: {} bytes", diagramId, zip.length);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artefacto + ".zip\"")
                .body(zip);
    }

    /**
     * Rebase de la cola offline (§9.2). Devuelve el reporte de reconciliación y difunde a la sala
     * un único evento con el estado ya reconciliado.
     *
     * <p>La difusión ocurre <b>después</b> de que el servicio transaccional retornó, o sea con el
     * commit hecho: si el rebase falla, nadie llegó a ver un estado que el servidor no tiene.
     */
    @PostMapping("/{diagramId}/sync-offline")
    public ReconciliationReport syncOffline(
            @PathVariable UUID diagramId,
            @Valid @RequestBody OfflineSyncRequest peticion,
            Principal emisor) {

        UUID userId = userId(emisor);
        exigirPermisoDeEscritura(diagramId, userId);

        ReconciliationReport reporte =
                reconciliationService.reconciliar(diagramId, userId, peticion);

        // Sólo se avisa si algo cambió: un lote que se descartó entero no tiene nada que
        // contarle a la sala, y despertar a veinte lienzos para nada es ruido.
        if (reporte.appliedCount() > 0) {
            messagingTemplate.convertAndSend(
                    "/topic/diagrams/" + diagramId,
                    OfflineSyncBroadcast.de(userId.toString(), reporte));
        }
        log.debug("Rebase de {} por {}: {} aplicadas, {} descartadas",
                diagramId, userId, reporte.appliedCount(), reporte.discardedCount());
        return reporte;
    }

    // ------------------------------------------------------------- utilitarios

    /**
     * Nombre del artefacto Maven a partir del nombre del diagrama. Pasa por el saneador y baja a
     * minúsculas porque un {@code artifactId} con mayúsculas o espacios no es válido, y el nombre
     * del diagrama lo escribe una persona: "Diagrama de Clases" tiene que dar algo instalable.
     */
    private String artefactoDe(String nombreDelDiagrama) {
        String limpio = sanitizer.nombreDeTabla(nombreDelDiagrama)
                .replace("\"", "")
                .replace('_', '-');
        return limpio.isBlank() ? "backend-generado" : limpio;
    }

    private Diagram traer(UUID diagramId) {
        return diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El diagrama no existe"));
    }

    private static UUID userId(Principal emisor) {
        return UUID.fromString(emisor.getName());
    }

    /** Leer exige pertenecer al proyecto; el rol da igual. */
    private void exigirMembresia(UUID diagramId, UUID userId) {
        if (projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No sos miembro del proyecto de este diagrama");
        }
    }

    /**
     * Mismo arbitraje de roles que hace el {@code StompChannelInterceptor} sobre STOMP. Sin esto
     * el endpoint sería la puerta de atrás: un VIEWER no podría mutar por WebSocket pero sí
     * reescribir el diagrama entero mandando su "cola offline" por REST.
     */
    private void exigirPermisoDeEscritura(UUID diagramId, UUID userId) {
        Optional<ProjectRole> rol =
                projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId);
        if (rol.isEmpty() || rol.get() == ProjectRole.VIEWER) {
            // Igual que en STOMP, no se distingue "no existe" de "no sos miembro": quien no
            // pertenece al proyecto tampoco debería averiguar qué diagramas existen.
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No tenés permiso de escritura sobre este diagrama");
        }
    }
}
