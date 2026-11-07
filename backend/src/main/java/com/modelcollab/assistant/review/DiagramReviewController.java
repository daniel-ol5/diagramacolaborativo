package com.modelcollab.assistant.review;

import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoint REST para solicitar la revisión arquitectónica del diagrama por la IA (CU22).
 *
 * <p>{@code POST /api/v1/diagrams/{diagramId}/review}
 *
 * <p>Permitido para cualquier miembro del proyecto (incluso VIEWER, pues la revisión es una
 * lectura que produce un informe y publica en el chat del proyecto sin modificar el diagrama).
 */
@RestController
@RequestMapping("/api/v1/diagrams")
public class DiagramReviewController {

    private static final Logger log = LoggerFactory.getLogger(DiagramReviewController.class);

    private final DiagramReviewService reviewService;

    public DiagramReviewController(DiagramReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/{diagramId}/review")
    public ResponseEntity<DiagramReviewResponse> review(
            @PathVariable UUID diagramId,
            Principal principal) {
        try {
            UUID userId = UUID.fromString(principal.getName());
            DiagramReviewResponse response = reviewService.reviewDiagram(diagramId, userId);
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fallo inesperado al revisar diagrama {}: {}", diagramId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Error al revisar diagrama: " + e.getMessage(), e);
        }
    }
}

