package com.modelcollab.vision.controller;

import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import com.modelcollab.vision.dto.VisionConfirmRequest;
import com.modelcollab.vision.dto.VisionProposalResponse;
import com.modelcollab.vision.service.VisionImageValidator;
import com.modelcollab.vision.service.VisionImportService;
import com.modelcollab.vision.service.VisionMerger;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * CU17: importación de un diagrama desde una foto (§14, RF-03).
 *
 * <h2>Dos endpoints, y esa es la garantía</h2>
 *
 * <p>Leer la foto y aplicar lo leído son dos peticiones distintas, con la persona en el medio. No
 * es una comodidad de diseño: es lo que hace que el <i>human-in-the-loop</i> de RF-03.3 sea
 * estructural en vez de una promesa. El endpoint que mira la imagen <b>no tiene forma de
 * escribir</b> — no recibe el servicio de mutación ni la plantilla de mensajería—, así que no
 * existe el camino por el que una foto se aplique sin que alguien la confirme.
 */
@RestController
public class VisionController {

    private static final Logger log = LoggerFactory.getLogger(VisionController.class);

    private final VisionImportService importService;
    private final ProjectMemberRepository projectMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public VisionController(
            VisionImportService importService,
            ProjectMemberRepository projectMemberRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.importService = importService;
        this.projectMemberRepository = projectMemberRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Analiza la foto y devuelve la propuesta. No modifica el diagrama.
     *
     * <p>Exige permiso de escritura aunque no escriba: subir una foto consume una llamada a un
     * servicio que se paga, y el resultado sólo le sirve a quien después va a poder confirmarlo.
     * Dejar que un VIEWER gaste lecturas para nada sería regalar la cuenta.
     */
    @PostMapping("/api/v1/diagrams/{diagramId}/vision-import")
    public VisionProposalResponse analizar(
            @PathVariable UUID diagramId,
            @RequestParam("image") MultipartFile archivo,
            Principal emisor) {

        UUID userId = userId(emisor);
        exigirPermisoDeEscritura(diagramId, userId);

        byte[] imagen;
        try {
            imagen = archivo == null ? null : archivo.getBytes();
        } catch (IOException e) {
            throw new VisionImageValidator.ImagenRechazadaException(
                    "No se pudo leer el archivo que subiste.");
        }
        // La imagen vive en este método y en el servicio, y se va con ellos. No se guarda.
        return importService.analizar(diagramId, imagen);
    }

    /** Aplica lo que la persona revisó y confirmó, como un único evento para la sala. */
    @PostMapping("/api/v1/diagrams/{diagramId}/vision-import/confirm")
    public ResponseEntity<Map<String, Object>> confirmar(
            @PathVariable UUID diagramId,
            @Valid @RequestBody VisionConfirmRequest peticion,
            Principal emisor) {

        UUID userId = userId(emisor);
        exigirPermisoDeEscritura(diagramId, userId);

        VisionImportService.Resultado resultado =
                importService.confirmar(diagramId, userId, peticion);

        // Después del commit, y uno solo para todo el sub-grafo.
        if (resultado.difusion() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/diagrams/" + diagramId, resultado.difusion());
        }
        log.debug("Confirmación de foto en {}: +{} clase(s)", diagramId, resultado.clasesNuevas());

        return ResponseEntity.ok(Map.of(
                "applied", resultado.difusion() != null,
                "addedClasses", resultado.clasesNuevas(),
                "addedRelationships", resultado.relacionesNuevas(),
                "mutationVersion", resultado.mutationVersion()));
    }

    /**
     * Un archivo tan grande que Spring lo corta antes de que llegue el controlador.
     *
     * <p>Sin este manejador la respuesta es un <b>413 con el cuerpo vacío</b>: el usuario sube
     * una foto, no pasa nada y no hay ningún texto que leer. El límite de Spring está puesto por
     * encima del que aplica el validador justamente para que casi siempre hable el validador,
     * que explica cuánto pesa y qué hacer; esto es la red por si alguien sube algo absurdo.
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> demasiadoGrande(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("reason", "INVALID_IMAGE",
                        "message", "La imagen es demasiado grande. El máximo son 8 MB: "
                                + "sacale resolución y volvé a intentar."));
    }

    /** La imagen no sirve y quien la subió puede arreglarlo: 400. */
    @ExceptionHandler(VisionImageValidator.ImagenRechazadaException.class)
    public ResponseEntity<Map<String, String>> imagenRechazada(
            VisionImageValidator.ImagenRechazadaException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("reason", "INVALID_IMAGE", "message", e.getMessage()));
    }

    /**
     * El borrador confirmado no sirve: <b>400</b> y no 502.
     *
     * <p>La diferencia con CU16 importa. Allá lo que se valida viene directo del modelo, así que
     * un incumplimiento es culpa del servicio de arriba. Acá el borrador pasó por el modal y lo
     * manda el navegador: es una petición de un cliente, y un cliente que manda algo inválido
     * recibe un 4xx. Si esto fuera 502, el panel de monitoreo diría "la IA está fallando" cada
     * vez que alguien confirma un tipo mal escrito a mano.
     */
    @ExceptionHandler(VisionMerger.BorradorInvalidoException.class)
    public ResponseEntity<Map<String, String>> borradorInvalido(
            VisionMerger.BorradorInvalidoException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("reason", "INVALID_DRAFT", "message", e.getMessage()));
    }

    /** El servicio de visión falló o rompió el contrato: 502. */
    @ExceptionHandler(RespuestaDeIaInvalidaException.class)
    public ResponseEntity<Map<String, String>> falloDeLaIa(RespuestaDeIaInvalidaException e) {
        log.warn("El servicio de visión rompió el contrato: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("reason", e.getMotivo(), "message", e.getMessage()));
    }

    private static UUID userId(Principal emisor) {
        return UUID.fromString(emisor.getName());
    }

    private void exigirPermisoDeEscritura(UUID diagramId, UUID userId) {
        Optional<ProjectRole> rol =
                projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId);
        if (rol.isEmpty() || rol.get() == ProjectRole.VIEWER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No tenés permiso de escritura sobre este diagrama");
        }
    }
}
