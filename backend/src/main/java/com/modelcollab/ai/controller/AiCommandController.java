package com.modelcollab.ai.controller;

import com.modelcollab.ai.dto.AiCommandRequest;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiCommandService;
import com.modelcollab.ai.service.AiModelClient;
import com.modelcollab.ai.service.ComandoRechazadoException;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** CU16: modelado por comando de voz o texto (§14, RF-02). */
@RestController
public class AiCommandController {

    private static final Logger log = LoggerFactory.getLogger(AiCommandController.class);

    private final AiCommandService commandService;
    private final AiModelClient cliente;
    private final ProjectMemberRepository projectMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public AiCommandController(
            AiCommandService commandService,
            AiModelClient cliente,
            ProjectMemberRepository projectMemberRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.commandService = commandService;
        this.cliente = cliente;
        this.projectMemberRepository = projectMemberRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Procesa una orden dictada o escrita y la aplica al diagrama.
     *
     * <p>Exige permiso de escritura. Es el mismo arbitraje que hace el interceptor de STOMP y
     * que ya hubo que repetir en {@code sync-offline}: <b>cada puerta nueva al estado del
     * diagrama vuelve a plantear la misma pregunta</b>. Sin esto, un VIEWER no podría mover una
     * caja con el ratón pero sí dictando "mueve la clase Cliente a la derecha".
     *
     * <p>La difusión sale <b>después</b> de que el servicio transaccional volvió, o sea con el
     * commit hecho: si el comando falla a mitad, nadie llegó a ver un estado que el servidor no
     * tiene.
     */
    @PostMapping("/api/v1/diagrams/{diagramId}/ai-command")
    public ResponseEntity<AiCommandResponse> comando(
            @PathVariable UUID diagramId,
            @Valid @RequestBody AiCommandRequest peticion,
            Principal emisor) {

        UUID userId = UUID.fromString(emisor.getName());
        exigirPermisoDeEscritura(diagramId, userId);

        AiCommandService.Resultado resultado =
                commandService.procesar(diagramId, userId, peticion);

        resultado.difusiones().forEach(difusion ->
                messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId, difusion));

        log.debug("Comando de IA en {} ({}): aplicado={} motivo={}",
                diagramId, cliente.descripcion(),
                resultado.respuesta().applied(), resultado.respuesta().reason());
        return responder(HttpStatus.OK, resultado.respuesta());
    }

    /**
     * La única salida: toda respuesta se estampa acá con de dónde salió.
     *
     * <p><b>Existe para que no haya que acordarse.</b> Hay tres puertas —ésta y los dos
     * manejadores de excepción— y el campo {@code simulated} tiene que decir la verdad en las
     * tres. Si cada una lo pusiera por su cuenta, la que se olvidara diría {@code false}, que
     * significa <i>"esto lo contestó un modelo real"</i>: la omisión miente, y miente en la
     * dirección peligrosa. Es la misma familia de error que el {@code mappedBy} y que el prefijo
     * {@code /api} — una regla que varios sitios tienen que respetar y nadie confronta—, así que
     * se resuelve igual: un solo lugar, y un test que recorre las tres puertas.
     */
    private ResponseEntity<AiCommandResponse> responder(
            HttpStatus estado, AiCommandResponse cuerpo) {
        return ResponseEntity.status(estado).body(cuerpo.conSimulado(cliente.esSimulado()));
    }

    /**
     * Un rechazo es una respuesta, no un error: <b>200</b>.
     *
     * <p>"No genero dominios completos" y "esa clase no existe" son contestaciones del asistente
     * que el navegador muestra en la conversación igual que cualquier otra. Devolverlas como 4xx
     * obligaría al cliente a leer cuerpos de error para mostrar texto normal, y ensuciaría las
     * métricas de error con el uso previsto del sistema.
     */
    @ExceptionHandler(ComandoRechazadoException.class)
    public ResponseEntity<AiCommandResponse> rechazo(ComandoRechazadoException e) {
        return responder(HttpStatus.OK, AiCommandResponse.rechazada(e.getMotivo(), e.getMessage()));
    }

    /**
     * El servicio de arriba falló o rompió el contrato: <b>502</b>.
     *
     * <p>Acá sí corresponde un 5xx, y la distinción no es teórica: es lo que permite que un
     * panel de monitoreo muestre "la IA está devolviendo basura" sin que el ruido de los
     * rechazos normales lo tape. El cuerpo igual trae un mensaje legible, porque el usuario
     * merece enterarse de que el diagrama no se tocó y de que no fue culpa suya.
     */
    @ExceptionHandler(RespuestaDeIaInvalidaException.class)
    public ResponseEntity<AiCommandResponse> falloDeLaIa(RespuestaDeIaInvalidaException e) {
        log.warn("El servicio de IA rompió el contrato: {}", e.getMessage());
        return responder(
                HttpStatus.BAD_GATEWAY, AiCommandResponse.rechazada(e.getMotivo(), e.getMessage()));
    }

    /**
     * Cualquier otro fallo inesperado: <b>500, pero con un cuerpo que se puede leer</b>.
     *
     * <h2>Por qué existe</h2>
     *
     * <p>Sin esto, un fallo no previsto sale como <b>500 con el cuerpo vacío</b>. El usuario dictó
     * algo, no pasó nada, y no hay ni un texto que leer — y lo peor: <b>no sabe si se aplicó
     * algo</b>. Ya nos pasó en vivo, y aunque la causa de aquella vez era otra, el 500 pelado la
     * hizo mucho más difícil de diagnosticar.
     *
     * <h2>Y por qué NO se traga todo, que es la parte delicada</h2>
     *
     * <p>Este proyecto ya tuvo un incidente donde el manejo de errores devoraba códigos de
     * estado: <b>toda respuesta de error salía convertida en 403 con cuerpo vacío</b>. Un
     * manejador de {@code RuntimeException} es exactamente la forma de repetirlo, porque
     * {@link ResponseStatusException} —el 403 de permisos de acá arriba— también es una
     * {@code RuntimeException}.
     *
     * <p>Por eso lo primero que hace es <b>devolver lo que ya trae su propio estado</b>. Spring
     * marca esas excepciones con {@link ErrorResponse}: el 403 de permisos, el 400 de un cuerpo
     * ilegible, el 405 de un método equivocado. Todas siguen saliendo como lo que son.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<AiCommandResponse> falloInesperado(RuntimeException e) {
        if (traeSuPropioEstado(e)) {
            // No es nuestra para contestar: ya sabe qué estado tiene.
            throw e;
        }
        log.error("Fallo inesperado procesando un comando de IA", e);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, AiCommandResponse.rechazada(
                AiCommandResponse.ERROR_INESPERADO,
                "Algo falló al procesar el comando. El diagrama no se tocó."));
    }

    /**
     * Si la excepción ya sabe con qué estado tiene que salir.
     *
     * <p>Son tres casos y los tres hicieron falta:
     *
     * <ul>
     *   <li>{@link ErrorResponse} — lo que Spring marca explícitamente, como el
     *       {@code ResponseStatusException} del 403 de permisos.</li>
     *   <li>Una excepción anotada con {@code @ResponseStatus}, que es la otra forma de
     *       declararlo.</li>
     *   <li><b>Cualquier excepción del propio Spring.</b> Ésta la agregué porque el test la
     *       pidió: {@code HttpMessageNotReadableException} —un cuerpo JSON ilegible, que
     *       corresponde a un <b>400</b>— <b>no</b> implementa {@code ErrorResponse}, así que con
     *       las dos primeras reglas se convertía en un 500. Si el marco la lanzó, el marco sabe
     *       qué estado merece mejor que nosotros.</li>
     * </ul>
     */
    private static boolean traeSuPropioEstado(RuntimeException e) {
        return e instanceof ErrorResponse
                || e.getClass().isAnnotationPresent(
                        org.springframework.web.bind.annotation.ResponseStatus.class)
                || e.getClass().getName().startsWith("org.springframework.");
    }

    private void exigirPermisoDeEscritura(UUID diagramId, UUID userId) {
        Optional<ProjectRole> rol =
                projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId);
        if (rol.isEmpty() || rol.get() == ProjectRole.VIEWER) {
            // No se distingue "no existe" de "no sos miembro", igual que en el resto de la API:
            // quien no pertenece al proyecto tampoco debería averiguar qué diagramas existen.
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "No tenés permiso de escritura sobre este diagrama");
        }
    }
}
