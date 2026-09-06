package com.modelcollab.collaboration.interceptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.auth.model.User;
import com.modelcollab.auth.repository.UserRepository;
import com.modelcollab.auth.service.JwtService;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.dto.StompErrorMessage;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Arbitraje de seguridad del canal STOMP entrante. Es el equivalente WebSocket de la cadena de
 * filtros de Spring Security: STOMP no pasa por los filtros del servlet, así que sin esta clase
 * el socket sería un agujero por el que cualquiera mutaría cualquier diagrama.
 *
 * <p>Hace dos cosas, en dos momentos distintos:
 *
 * <ol>
 *   <li><b>CONNECT — autenticación.</b> Exige {@code Authorization: Bearer <jwt>} como header
 *       nativo del frame y asocia un {@link StompPrincipal} a la sesión. Sin token válido la
 *       conexión se rechaza y no llega a existir.
 *   <li><b>SEND a {@code /app/diagram/&#123;id&#125;/**} — autorización.</b> Resuelve el rol del
 *       usuario sobre ese diagrama y descarta la operación si es {@code VIEWER} o si no hay
 *       membresía.
 * </ol>
 *
 * <h2>Por qué el 403 descarta el mensaje en vez de lanzar</h2>
 *
 * <p>Lo natural sería lanzar una excepción desde {@code preSend}, pero eso emite un frame ERROR
 * y <b>STOMP cierra la sesión</b>: un VIEWER que sin querer arrastra un nodo quedaría expulsado
 * de la sala y dejaría de ver los cambios de los demás — un rol de sólo lectura que se
 * autodestruye al mirar. En su lugar {@code preSend} devuelve {@code null}, con lo que el
 * mensaje se descarta y la conexión sobrevive, y el error se entrega al solicitante por su canal
 * privado {@code /user/queue/errors}. El VIEWER se entera de que no puede editar y sigue viendo
 * el diagrama en vivo, que es exactamente lo que significa ser VIEWER.
 *
 * <p>El CONNECT sí lanza: ahí cerrar la conexión es la respuesta correcta, porque una sesión sin
 * identidad no tiene nada que hacer abierta.
 *
 * <h2>Por qué el rol se cachea en la sesión</h2>
 *
 * <p>{@code MOVE_CLASS} viaja a ~60 mensajes por segundo mientras se arrastra una clase (§8.1,
 * "LWW libre" justamente para que el arrastre sea fluido). Consultar la base en cada uno, con
 * veinte colaboradores, son 1200 consultas por segundo para responder siempre lo mismo. El rol
 * se resuelve una vez por diagrama y queda en los atributos de la sesión WebSocket.
 *
 * <p><b>Consecuencia asumida:</b> el rol vive lo que vive la sesión. Si a un EDITOR se le baja a
 * VIEWER mientras está conectado, sigue editando hasta que se reconecte. Es aceptable porque el
 * cambio de rol no es una operación de emergencia y la sesión muere al cerrar la pestaña; si
 * alguna vez hiciera falta revocación inmediata, el lugar es publicar un evento a la sala que
 * invalide esta entrada, no volver a consultar por mensaje.
 */
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompChannelInterceptor.class);

    /** Destinos que este interceptor arbitra: {@code /app/diagram/{diagramId}/<lo que sea>}. */
    private static final Pattern DESTINO_DIAGRAMA =
            Pattern.compile("^/app/diagram/(?<diagramId>[^/]+)/.+$");

    /**
     * Destinos de diagrama que un VIEWER SI puede usar: los de presencia. Ver {@link #autorizar}.
     */
    private static final java.util.Set<String> SUFIJOS_DE_PRESENCIA =
            java.util.Set.of("/cursor", "/presence");

    private static final String HEADER_AUTH = "Authorization";
    private static final String PREFIJO_BEARER = "Bearer ";
    private static final String COLA_ERRORES = "/queue/errors";
    private static final String PREFIJO_CACHE_ROL = "role:";
    /** Clave del Participante en los atributos de la sesion; la leen locks y presencia. */
    static final String ATRIBUTO_PARTICIPANTE = "participante";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public StompChannelInterceptor(
            JwtService jwtService,
            UserRepository userRepository,
            ProjectMemberRepository projectMemberRepository,
            // @Lazy rompe un ciclo real de beans: WebSocketConfig necesita este interceptor
            // para armar el canal entrante, y SimpMessagingTemplate lo crea la propia
            // infraestructura de mensajería que WebSocketConfig configura.
            @Lazy SimpMessagingTemplate messagingTemplate,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        return switch (accessor.getCommand()) {
            case CONNECT -> autenticar(accessor, message);
            case SEND -> autorizar(accessor, message);
            default -> message;
        };
    }

    // ---------------------------------------------------------------- CONNECT

    private Message<?> autenticar(StompHeaderAccessor accessor, Message<?> message) {
        String header = accessor.getFirstNativeHeader(HEADER_AUTH);
        if (header == null || !header.startsWith(PREFIJO_BEARER)) {
            throw new MessageDeliveryException(
                    message, "CONNECT sin header Authorization: Bearer <jwt>");
        }
        UUID userId;
        try {
            userId = jwtService.parse(header.substring(PREFIJO_BEARER.length()).trim());
        } catch (RuntimeException e) {
            // El motivo exacto (vencido, firma inválida, mal formado) queda en el log del
            // servidor y no en el frame ERROR: al cliente no le sirve para nada y a un
            // atacante le sirve para calibrar.
            log.debug("CONNECT rechazado, token inválido: {}", e.getMessage());
            throw new MessageDeliveryException(message, "CONNECT con token inválido o vencido");
        }
        accessor.setUser(new StompPrincipal(userId));

        // Se resuelve el Participante (nombre y color) una sola vez, aca, y queda en los
        // atributos de la sesion. Los locks lo necesitan en cada LOCK_ACQUIRED para que la sala
        // pueda pintar "Editando por Ana" con su color; volver a la base por el nombre en cada
        // mensaje seria el mismo error que la cache del rol vino a resolver. Una consulta por
        // sesion, no por mensaje.
        String nombre = userRepository.findById(userId).map(User::getFullName).orElse(null);
        if (nombre == null) {
            // Token con firma valida pero cuyo usuario ya no existe: la cuenta se borro despues
            // de emitirlo. El token sigue siendo criptograficamente valido y por eso hay que
            // rechazarlo aca a mano.
            throw new MessageDeliveryException(message, "El usuario del token ya no existe");
        }
        Map<String, Object> sesion = accessor.getSessionAttributes();
        if (sesion != null) {
            sesion.put(ATRIBUTO_PARTICIPANTE, Participante.de(userId, nombre));
        }

        log.debug("CONNECT aceptado para {} ({})", nombre, userId);
        return message;
    }

    // ------------------------------------------------------------------- SEND

    private Message<?> autorizar(StompHeaderAccessor accessor, Message<?> message) {
        String destino = accessor.getDestination();
        if (destino == null) {
            return message;
        }
        Matcher matcher = DESTINO_DIAGRAMA.matcher(destino);
        if (!matcher.matches()) {
            // No es un destino de diagrama; esta regla no opina. Que llegue o no a un handler
            // lo decide el enrutamiento normal de Spring.
            return message;
        }

        Principal principal = accessor.getUser();
        if (principal == null) {
            // No debería ocurrir: sin CONNECT válido no hay sesión. Se descarta igual en vez de
            // confiar en esa invariante, porque el costo de equivocarse acá es una mutación
            // anónima aplicada al diagrama de otro.
            log.warn("SEND a {} sin Principal en la sesión; se descarta", destino);
            return null;
        }

        UUID diagramId;
        UUID userId;
        try {
            diagramId = UUID.fromString(matcher.group("diagramId"));
            userId = UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            log.warn("SEND a {} con identificador no-UUID; se descarta", destino);
            return null;
        }

        Optional<ProjectRole> rol = rolDeLaSesion(accessor, diagramId, userId);

        // El cursor y el pedido de roster son presencia, no edicion: un VIEWER puede usarlos.
        // Mirar donde apunta alguien que mira, y saber quien esta en la sala, es exactamente lo
        // que un rol de solo lectura deberia poder hacer; sin esto los demas no verian su
        // puntero y el no veria a nadie. La membresia si se exige igual: quien no pertenece al
        // proyecto no entra ni a mirar.
        boolean esPresencia = SUFIJOS_DE_PRESENCIA.stream().anyMatch(destino::endsWith);

        if (rol.isEmpty() || (!esPresencia && rol.get() == ProjectRole.VIEWER)) {
            rechazar(message, destino, userId, rol);
            return null;
        }
        return message;
    }

    private void rechazar(
            Message<?> message, String destino, UUID userId, Optional<ProjectRole> rol) {
        String motivo =
                rol.isPresent()
                        ? "El rol VIEWER no puede modificar el diagrama"
                        : "No sos miembro de este proyecto";
        log.debug("403 en {} para el usuario {}: {}", destino, userId, motivo);

        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                COLA_ERRORES,
                StompErrorMessage.rolInsuficiente(destino, clientMutationIdDe(message), motivo));
    }

    // ------------------------------------------------------------ rol y caché

    private Optional<ProjectRole> rolDeLaSesion(
            StompHeaderAccessor accessor, UUID diagramId, UUID userId) {
        Map<String, Object> sesion = accessor.getSessionAttributes();
        String clave = PREFIJO_CACHE_ROL + diagramId;

        if (sesion != null && sesion.get(clave) instanceof Optional<?> cacheado) {
            @SuppressWarnings("unchecked")
            Optional<ProjectRole> rol = (Optional<ProjectRole>) cacheado;
            return rol;
        }

        Optional<ProjectRole> rol =
                projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId);
        if (sesion != null) {
            // Se cachea también el "sin membresía": si no, quien no pertenece al proyecto
            // provoca una consulta por cada mensaje que mande, que es justo el patrón que esta
            // caché existe para evitar.
            sesion.put(clave, rol);
        }
        return rol;
    }

    /**
     * El Participante que se resolvio en el CONNECT. Lo leen el controlador de locks y el
     * listener de presencia, que no tienen forma de recalcularlo sin volver a la base.
     */
    public static Participante participanteDe(StompHeaderAccessor accessor) {
        Map<String, Object> sesion = accessor.getSessionAttributes();
        if (sesion == null) {
            return null;
        }
        return sesion.get(ATRIBUTO_PARTICIPANTE) instanceof Participante p ? p : null;
    }

    /**
     * Extrae {@code clientMutationId} del cuerpo crudo para poder devolverlo en el error. Se
     * hace sólo en el camino de rechazo: en el camino feliz el mensaje no se toca y la
     * deserialización la hace el converter, una sola vez, más adelante.
     */
    private String clientMutationIdDe(Message<?> message) {
        if (!(message.getPayload() instanceof byte[] cuerpo) || cuerpo.length == 0) {
            return null;
        }
        try {
            JsonNode campo = objectMapper.readTree(cuerpo).get("clientMutationId");
            return campo == null || campo.isNull() ? null : campo.asText();
        } catch (Exception e) {
            // Un cuerpo ilegible no debe impedir que el 403 llegue; se manda con el campo nulo.
            return null;
        }
    }
}
