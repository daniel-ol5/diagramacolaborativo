package com.modelcollab.diagram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.diagram.dto.DiagramSnapshotResponse;
import com.modelcollab.diagram.dto.OfflineSyncBroadcast;
import com.modelcollab.diagram.dto.OfflineSyncRequest;
import com.modelcollab.diagram.dto.ReconciliationReport;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.diagram.service.OfflineReconciliationService;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Endpoints REST del diagrama (§14): snapshot del estado y rebase de la cola offline. */
@RestController
@RequestMapping("/api/v1/diagrams")
public class DiagramController {

    private static final Logger log = LoggerFactory.getLogger(DiagramController.class);

    private final OfflineReconciliationService reconciliationService;
    private final DiagramRepository diagramRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public DiagramController(
            OfflineReconciliationService reconciliationService,
            DiagramRepository diagramRepository,
            ProjectMemberRepository projectMemberRepository,
            SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper) {
        this.reconciliationService = reconciliationService;
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
     *
     * <p>Es la pieza que le falta al cliente para no depender de un fixture local: sin esto,
     * recargar la página después de cualquier mutación muestra el estado viejo. Y después de un
     * rebase es obligatorio, porque el cliente necesita recargar el estado ya reconciliado.
     */
    @GetMapping("/{diagramId}/snapshot")
    public DiagramSnapshotResponse snapshot(
            @PathVariable UUID diagramId, Principal emisor) {

        UUID userId = UUID.fromString(emisor.getName());
        if (projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No sos miembro del proyecto de este diagrama");
        }
        Diagram diagrama = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El diagrama no existe"));
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

        UUID userId = UUID.fromString(emisor.getName());
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
