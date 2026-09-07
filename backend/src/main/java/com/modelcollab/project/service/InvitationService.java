package com.modelcollab.project.service;

import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.project.dto.InvitationPreview;
import com.modelcollab.project.dto.InvitationRequest;
import com.modelcollab.project.dto.InvitationResponse;
import com.modelcollab.project.model.Project;
import com.modelcollab.project.model.ProjectInvitation;
import com.modelcollab.project.repository.ProjectInvitationRepository;
import com.modelcollab.project.repository.ProjectRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enlaces de invitación a un proyecto.
 *
 * <h2>Por qué una tabla y no un token firmado</h2>
 *
 * <p>Firmar {@code {projectId, role}} y no guardar nada sería más simple, y está mal: <b>un
 * enlace así no se puede revocar</b>. Los enlaces para compartir se filtran —se pegan en chats,
 * quedan en historiales, se reenvían sin pensar— y con una firma válida el proyecto quedaría
 * abierto para siempre. A la pregunta "¿y si alguien filtra el enlace?" hay que poder responder
 * algo mejor que "se pierde el proyecto". Una fila se revoca.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    /** 32 bytes = 256 bits de entropía. Un token adivinable es lo mismo que no tener token. */
    private static final int BYTES_DE_TOKEN = 32;

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final ProjectInvitationRepository invitationRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectService projectService;
    private final String baseDeUrls;
    private final Clock reloj;

    @Autowired
    public InvitationService(
            ProjectInvitationRepository invitationRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            ProjectService projectService,
            @Value("${app.public-base-url:http://localhost:5173}") String baseDeUrls) {
        this(invitationRepository, projectRepository, userRepository, projectService,
                baseDeUrls, Clock.systemUTC());
    }

    /** Constructor con reloj inyectable, para probar vencimientos sin dormir el hilo. */
    public InvitationService(
            ProjectInvitationRepository invitationRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            ProjectService projectService,
            String baseDeUrls,
            Clock reloj) {
        this.invitationRepository = invitationRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.projectService = projectService;
        this.baseDeUrls = baseDeUrls;
        this.reloj = reloj;
    }

    // ------------------------------------------------------------------ crear

    @Transactional
    public InvitationResponse crear(UUID projectId, UUID solicitante, InvitationRequest peticion) {
        projectService.exigirPropiedad(projectId, solicitante);

        Project proyecto = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El proyecto no existe"));
        User autor = userRepository.findById(solicitante).orElse(null);

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        OffsetDateTime vence = peticion.expiresInDays() == null
                ? null
                : ahora.plusDays(peticion.expiresInDays());

        ProjectInvitation invitacion = invitationRepository.save(
                new ProjectInvitation(proyecto, nuevoToken(), peticion.role(), autor, vence));

        // El token NO se registra en el log: un enlace de acceso en un archivo de texto que se
        // rota, se copia y se manda a soporte es una filtración esperando ocurrir.
        log.debug("Invitación {} creada para el proyecto {} con rol {}",
                invitacion.getId(), projectId, peticion.role());

        return InvitationResponse.de(invitacion, baseDeUrls, ahora);
    }

    private static String nuevoToken() {
        byte[] bytes = new byte[BYTES_DE_TOKEN];
        ALEATORIO.nextBytes(bytes);
        // base64url sin relleno: el token viaja en una URL y no debe necesitar escapado.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------------ mirar

    /**
     * Qué hay detrás de un enlace, sin agregar a nadie.
     *
     * <p>Siempre responde 200: que un token no exista no es un error del cliente, es la respuesta.
     * Y ante un token inválido <b>no se dice nada del proyecto</b> — si dijera el nombre, probar
     * tokens al azar se convertiría en una forma de descubrir qué proyectos hay.
     */
    @Transactional(readOnly = true)
    public InvitationPreview mirar(String token) {
        Optional<ProjectInvitation> encontrada = invitationRepository.findPorToken(token);
        if (encontrada.isEmpty()) {
            return InvitationPreview.invalida(InvitationPreview.INEXISTENTE);
        }
        ProjectInvitation invitacion = encontrada.get();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);

        if (invitacion.getRevokedAt() != null) {
            return InvitationPreview.invalida(InvitationPreview.REVOCADA);
        }
        if (!invitacion.estaVigente(ahora)) {
            return InvitationPreview.invalida(InvitationPreview.VENCIDA);
        }
        return InvitationPreview.valida(
                invitacion.getProject().getName(), invitacion.getRole());
    }

    // ---------------------------------------------------------------- aceptar

    /**
     * Acepta el enlace y devuelve el proyecto al que se entró.
     *
     * <p>Delega en {@code ProjectService.otorgar}, que es donde vive la regla de que un rol
     * <b>sube o no hace nada, pero nunca baja</b>. Tenerla ahí y no acá es lo que garantiza que
     * valga igual por este camino que agregando a alguien por correo: el dueño que abre su propio
     * enlace de VIEWER sigue siendo dueño, y aceptar tres veces es lo mismo que aceptar una.
     */
    @Transactional
    public UUID aceptar(String token, UUID userId) {
        ProjectInvitation invitacion = invitationRepository.findPorToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "El enlace no es válido"));

        if (!invitacion.estaVigente(OffsetDateTime.now(reloj))) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "El enlace ya no está vigente");
        }
        User usuario = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "El usuario del token ya no existe"));

        UUID projectId = invitacion.getProject().getId();
        projectService.otorgar(projectId, usuario, invitacion.getRole());
        log.debug("Usuario {} aceptó la invitación {} al proyecto {}",
                userId, invitacion.getId(), projectId);
        return projectId;
    }

    // ------------------------------------------------------- listar y revocar

    @Transactional(readOnly = true)
    public List<InvitationResponse> listar(UUID projectId, UUID solicitante) {
        projectService.exigirPropiedad(projectId, solicitante);
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        return invitationRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(i -> InvitationResponse.de(i, baseDeUrls, ahora))
                .toList();
    }

    /**
     * Revoca el enlace. Se marca la fila en vez de borrarla: así queda rastro de que existió, de
     * quién lo creó y de cuándo se cortó, que es lo que se quiere mirar justamente cuando hubo
     * una filtración.
     */
    @Transactional
    public void revocar(UUID projectId, UUID invitationId, UUID solicitante) {
        projectService.exigirPropiedad(projectId, solicitante);

        ProjectInvitation invitacion = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "La invitación no existe"));

        // Sin esta comprobación, el dueño de un proyecto podría revocar los enlaces de otro con
        // sólo conocer el id: la ruta lleva su projectId, pero el id de la invitación es de
        // cualquiera.
        if (!invitacion.getProject().getId().equals(projectId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "La invitación no es de este proyecto");
        }
        invitacion.revocar(OffsetDateTime.now(reloj));
    }
}
