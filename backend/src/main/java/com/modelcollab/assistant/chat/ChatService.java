package com.modelcollab.assistant.chat;

import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.assistant.core.ChatStore;
import com.modelcollab.assistant.core.SlidingWindowRateLimiter;
import com.modelcollab.assistant.core.UserMessageListener;
import com.modelcollab.collaboration.dto.Participante;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Chat grupal de un proyecto (CU19). <b>Es el único punto de entrada para escribir</b>: lo usan
 * el controlador STOMP y el REST del móvil, así que las reglas valen igual por los dos caminos.
 *
 * <p>Orden de cada mensaje de un usuario:
 *
 * <ol>
 *   <li>Membresía (cualquier rol, VIEWER incluido: el chat es conversación, no edición).
 *   <li>Texto no vacío y de hasta {@value #LARGO_MAXIMO} caracteres.
 *   <li>Idempotencia por {@code clientMessageId}: un duplicado devuelve el original y no se vuelve
 *       a difundir. Va <b>antes</b> del límite de frecuencia para que reintentar no consuma cupo.
 *   <li>Límite de {@value #MENSAJES_POR_MINUTO} mensajes por minuto por usuario.
 *   <li>seq, guardar, difundir —en ese orden y bajo el candado del proyecto—.
 *   <li>Avisar a los {@link UserMessageListener} (el orquestador de la IA).
 * </ol>
 *
 * <h2>Por qué un candado por proyecto si nextSeq ya es atómico</h2>
 *
 * <p>La atomicidad de {@code nextSeq} garantiza que no haya dos mensajes con el mismo número, pero
 * no que se <b>difundan</b> en orden: A toma el 7, B toma el 8, B difunde antes que A, y los
 * clientes ven 8 antes que 7. Además, si dos reintentos con el mismo {@code clientMessageId}
 * pasan los dos el chequeo, el segundo choca con el índice único después de haber consumido un
 * número y deja un hueco. Serializar "seq → guardar → difundir" por proyecto resuelve las dos
 * cosas; el costo es nulo porque el chat de un proyecto no tiene volumen para que importe.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public static final int LARGO_MAXIMO = 2000;
    public static final int MENSAJES_POR_MINUTO = 20;
    static final int LARGO_MAXIMO_CLIENT_ID = 100;
    public static final int HISTORIAL_POR_DEFECTO = 50;
    public static final int HISTORIAL_MAXIMO = 100;
    public static final int BUSQUEDA_MINIMA = 2;
    public static final int BUSQUEDA_MAXIMA = 100;
    public static final String NOMBRE_IA = "IA";
    public static final String NOMBRE_SISTEMA = "Sistema";
    static final String PREFIJO_IA = "ai-";
    static final String PREFIJO_SISTEMA = "sys-";

    private final ChatStore store;
    /** CU23. Null solo si alguien construye el servicio con un ChatStore que no lo implementa. */
    private final ChatHistoryStore historyStore;
    private final ChatPublisher publisher;
    private final ChatParticipants participants;
    private final List<UserMessageListener> listeners;
    private final Clock reloj;
    private final SlidingWindowRateLimiter<UUID> limitePorUsuario;
    private final Map<UUID, ReentrantLock> candados = new ConcurrentHashMap<>();

    @Autowired
    public ChatService(
            ChatStore store,
            ChatHistoryStore historyStore,
            ChatPublisher publisher,
            ChatParticipants participants,
            List<UserMessageListener> listeners) {
        this(store, historyStore, publisher, participants, listeners, Clock.systemUTC());
    }

    /**
     * Sin ChatHistoryStore explícito: lo toma del propio store si lo implementa (los dos de
     * {@code assistant/store} lo hacen). Así los tests que ya existían no cambian.
     */
    public ChatService(
            ChatStore store,
            ChatPublisher publisher,
            ChatParticipants participants,
            List<UserMessageListener> listeners) {
        this(store, publisher, participants, listeners, Clock.systemUTC());
    }

    /** Con reloj inyectable, para probar el límite por minuto sin esperar un minuto. */
    public ChatService(
            ChatStore store,
            ChatPublisher publisher,
            ChatParticipants participants,
            List<UserMessageListener> listeners,
            Clock reloj) {
        this(store, store instanceof ChatHistoryStore h ? h : null, publisher, participants, listeners, reloj);
    }

    public ChatService(
            ChatStore store,
            ChatHistoryStore historyStore,
            ChatPublisher publisher,
            ChatParticipants participants,
            List<UserMessageListener> listeners,
            Clock reloj) {
        this.store = store;
        this.historyStore = historyStore;
        this.publisher = publisher;
        this.participants = participants;
        this.listeners = List.copyOf(listeners);
        this.reloj = reloj;
        this.limitePorUsuario =
                new SlidingWindowRateLimiter<>(MENSAJES_POR_MINUTO, Duration.ofMinutes(1), reloj);
    }

    /** Escribe un mensaje de usuario resolviendo su nombre en la base. Lo usa REST. */
    public ChatPostResult postFromUser(UUID projectId, Principal principal, ChatPostRequest request) {
        return postFromUser(projectId, principal, request, null);
    }

    /**
     * Igual, reutilizando el {@link Participante} que STOMP ya resolvió en el CONNECT para no
     * volver a la base por el nombre. Si no coincide con el Principal se ignora: la identidad
     * manda el Principal, nunca otra cosa.
     */
    public ChatPostResult postFromUser(
            UUID projectId,
            Principal principal,
            ChatPostRequest request,
            Participante deLaSesion) {
        UUID userId = usuarioDe(principal);
        if (participants.roleIn(projectId, userId).isEmpty()) {
            throw new ChatRejectedException(
                    ChatErrorCode.NOT_PROJECT_MEMBER, "No sos miembro de este proyecto");
        }
        String texto = validarTexto(request);
        String clientMessageId = validarClientMessageId(request.clientMessageId());
        String proyecto = projectId.toString();

        Optional<ChatPostResult> repetido = duplicado(proyecto, clientMessageId, userId.toString());
        if (repetido.isPresent()) {
            return repetido.get();
        }
        if (request.replyToId() != null
                && store.findById(proyecto, request.replyToId()).isEmpty()) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "El mensaje al que respondés no existe");
        }
        // Antes de guardar: uno inexistente rompería la FK (500) y uno de otro proyecto se
        // filtraría al chat de éste. Mismo error para los dos, sin decir cuál fue.
        if (request.diagramId() != null
                && !participants.diagramBelongsToProject(request.diagramId(), projectId)) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "El diagrama no es válido para este proyecto");
        }
        if (!limitePorUsuario.tryAcquire(userId)) {
            throw new ChatRejectedException(
                    ChatErrorCode.RATE_LIMITED,
                    "Estás enviando mensajes muy rápido. Esperá un momento.");
        }

        Participante autor = autor(userId, deLaSesion);
        ChatPostResult resultado =
                agregar(
                        projectId,
                        clientMessageId,
                        userId.toString(),
                        seq ->
                                new ChatMessage(
                                        UUID.randomUUID().toString(),
                                        proyecto,
                                        request.diagramId() == null
                                                ? null
                                                : request.diagramId().toString(),
                                        seq,
                                        ChatAuthorType.USER,
                                        userId.toString(),
                                        autor.userName(),
                                        autor.color(),
                                        null,
                                        null,
                                        request.replyToId(),
                                        clientMessageId,
                                        ChatMessageKind.CHAT,
                                        texto,
                                        reloj.instant(),
                                        null));

        if (!resultado.duplicate()) {
            avisar(resultado.message());
        }
        return resultado;
    }

    // --------------------------------------------------------------- lectura

    /**
     * Historial del chat para cualquier miembro (VIEWER incluido), siempre ascendente por seq.
     *
     * <ul>
     *   <li>Sin cursores: los últimos {@code limit}; {@code hasMore} = hay más viejos.
     *   <li>{@code beforeSeq}: los inmediatamente anteriores; {@code hasMore} = hay más viejos.
     *   <li>{@code afterSeq}: los inmediatamente posteriores (los más cercanos, no los últimos);
     *       {@code hasMore} = hay más nuevos, el cliente sigue pidiendo desde el último seq.
     *   <li>Los dos cursores a la vez, o uno negativo: {@code INVALID_PAYLOAD}.
     * </ul>
     *
     * <p>{@code limit}: {@value #HISTORIAL_POR_DEFECTO} si viene nulo, y se acota a 1..{@value
     * #HISTORIAL_MAXIMO}. {@code hasMore} se calcula pidiendo uno de más al store.
     */
    public ChatHistoryPage history(
            UUID projectId, Principal principal, Long beforeSeq, Long afterSeq, Integer limit) {
        return history(projectId, principal, beforeSeq, afterSeq, limit, null, null, null);
    }

    /**
     * Igual que {@link #history(UUID, Principal, Long, Long, Integer)}, con filtros opcionales y
     * combinables (CU23). Los cursores, {@code limit} y {@code hasMore} funcionan igual, pero
     * <b>sobre el conjunto filtrado</b>.
     *
     * <ul>
     *   <li>{@code q}: se recorta; de {@value #BUSQUEDA_MINIMA} a {@value #BUSQUEDA_MAXIMA}
     *       caracteres. Texto literal ({@code %}, {@code _} y {@code \} no son comodines), sin
     *       distinguir mayúsculas, solo en este proyecto. Distingue acentos.
     *   <li>{@code authorType}: USER, AI o SYSTEM. {@code kind}: CHAT, ANSWER, REVIEW, SUMMARY o
     *       SYSTEM. Sin distinguir mayúsculas.
     *   <li>{@code null} = sin ese filtro. Cualquier otro valor inválido, en blanco incluido:
     *       {@code INVALID_PAYLOAD}.
     * </ul>
     *
     * <p>Primero la membresía, después los cursores y por último los filtros. Sin ningún filtro
     * se usa exactamente el camino de siempre ({@code latest}/{@code before}/{@code after}).
     */
    public ChatHistoryPage history(
            UUID projectId,
            Principal principal,
            Long beforeSeq,
            Long afterSeq,
            Integer limit,
            String q,
            String authorType,
            String kind) {
        exigirMiembro(projectId, principal);
        if (beforeSeq != null && afterSeq != null) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "Usá beforeSeq o afterSeq, no los dos");
        }
        if ((beforeSeq != null && beforeSeq < 0) || (afterSeq != null && afterSeq < 0)) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "beforeSeq y afterSeq no pueden ser negativos");
        }
        String busqueda = validarBusqueda(q);
        ChatAuthorType tipo = enumDe(ChatAuthorType.class, authorType, "authorType");
        ChatMessageKind clase = enumDe(ChatMessageKind.class, kind, "kind");
        int n = acotar(limit);
        boolean adelante = afterSeq != null;

        List<ChatMessage> leidos;
        if (busqueda == null && tipo == null && clase == null) {
            String proyecto = projectId.toString();
            leidos = adelante
                    ? store.after(proyecto, afterSeq, n + 1)
                    : beforeSeq != null
                            ? store.before(proyecto, beforeSeq, n + 1)
                            : store.latest(proyecto, n + 1);
        } else {
            leidos = historial().find(
                    new ChatHistoryQuery(projectId, beforeSeq, afterSeq, n + 1, busqueda, tipo, clase));
        }
        boolean hayMas = leidos.size() > n;
        if (!hayMas) {
            return new ChatHistoryPage(leidos, false);
        }
        // Hacia adelante el de más es el más nuevo (se descarta del final); hacia atrás es el
        // más viejo (se descarta del principio).
        return new ChatHistoryPage(
                adelante ? leidos.subList(0, n) : leidos.subList(leidos.size() - n, leidos.size()),
                true);
    }

    /** null = sin búsqueda; si no, recortado y de 2 a 100 caracteres (code points). */
    private static String validarBusqueda(String q) {
        if (q == null) {
            return null;
        }
        String recortado = q.strip();
        int largo = recortado.codePointCount(0, recortado.length());
        if (largo < BUSQUEDA_MINIMA || largo > BUSQUEDA_MAXIMA) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD,
                    "La búsqueda debe tener entre " + BUSQUEDA_MINIMA + " y " + BUSQUEDA_MAXIMA
                            + " caracteres");
        }
        return recortado;
    }

    /** null = sin filtro; si no, el nombre del enum sin distinguir mayúsculas. */
    private static <E extends Enum<E>> E enumDe(Class<E> tipo, String valor, String parametro) {
        if (valor == null) {
            return null;
        }
        try {
            return Enum.valueOf(tipo, valor.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "Valor inválido para " + parametro);
        }
    }

    // ------------------------------------------------- estado de lectura (CU23)

    /**
     * Hasta dónde leyó esta persona el chat del proyecto, cuántos mensajes de otros (IA y
     * sistema incluidos) tiene sin leer y cuál es el último seq. Cualquier miembro, VIEWER
     * incluido: leer no modifica el diagrama.
     */
    public ChatReadState readState(UUID projectId, Principal principal) {
        UUID userId = exigirMiembro(projectId, principal);
        return historial().readState(projectId, userId);
    }

    /**
     * Marca como leído hasta {@code upToSeq}. Solo avanza, se recorta al último seq del proyecto
     * y es idempotente. {@code upToSeq} nulo o negativo: {@code INVALID_PAYLOAD}.
     */
    public ChatReadState markRead(UUID projectId, Principal principal, Long upToSeq) {
        UUID userId = exigirMiembro(projectId, principal);
        if (upToSeq == null || upToSeq < 0) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "upToSeq es obligatorio y no puede ser negativo");
        }
        return historial().markRead(projectId, userId, upToSeq);
    }

    /** El usuario del Principal, si es miembro del proyecto (cualquier rol). */
    private UUID exigirMiembro(UUID projectId, Principal principal) {
        UUID userId = usuarioDe(principal);
        if (projectId == null || participants.roleIn(projectId, userId).isEmpty()) {
            throw new ChatRejectedException(
                    ChatErrorCode.NOT_PROJECT_MEMBER, "No sos miembro de este proyecto");
        }
        return userId;
    }

    private ChatHistoryStore historial() {
        if (historyStore == null) {
            throw new IllegalStateException("El ChatStore configurado no implementa ChatHistoryStore");
        }
        return historyStore;
    }

    private static int acotar(Integer limit) {
        if (limit == null) {
            return HISTORIAL_POR_DEFECTO;
        }
        return Math.max(1, Math.min(HISTORIAL_MAXIMO, limit));
    }

    // ------------------------------------------------- escritura de la IA y el sistema

    /**
     * Escribe un mensaje de la IA o del sistema por el mismo camino que los de las personas: el
     * mismo candado por proyecto, el siguiente seq, persistencia y difusión. <b>No avisa a los
     * {@link UserMessageListener}</b>: la IA no puede dispararse a sí misma.
     *
     * <ul>
     *   <li>{@code AI}: autor "IA" sin id ni color, dirigido a quien escribió {@code enRespuestaA}
     *       ({@code addressedToId/Name}), {@code replyToId} = su id, kind {@code ANSWER}, y el
     *       mismo {@code diagramId} como contexto. Idempotente por pregunta: su clientMessageId es
     *       {@code "ai-" + enRespuestaA.id()}, así que una pregunta no recibe dos respuestas.
     *   <li>{@code SYSTEM}: autor "Sistema", sin destinatario ni respuesta, kind {@code SYSTEM}.
     * </ul>
     *
     * <p>El texto se guarda tal cual (sin recortar ni dar formato); sólo se exige que no esté
     * vacío.
     *
     * @throws IllegalArgumentException si {@code authorType} es USER, si el texto está vacío, o
     *     si es AI sin {@code enRespuestaA}.
     */
    public ChatMessage postFromAi(
            UUID projectId, ChatAuthorType authorType, String text, ChatMessage enRespuestaA) {
        if (projectId == null || authorType == null || authorType == ChatAuthorType.USER) {
            throw new IllegalArgumentException("postFromAi es sólo para mensajes AI o SYSTEM");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("El mensaje de la IA o del sistema no puede estar vacío");
        }
        String proyecto = projectId.toString();
        if (authorType == ChatAuthorType.AI) {
            if (enRespuestaA == null) {
                throw new IllegalArgumentException("Una respuesta de la IA necesita la pregunta");
            }
            String clientId = PREFIJO_IA + enRespuestaA.id();
            return agregar(projectId, clientId, null,
                            seq -> new ChatMessage(
                                    UUID.randomUUID().toString(), proyecto, enRespuestaA.diagramId(), seq,
                                    ChatAuthorType.AI, null, NOMBRE_IA, null,
                                    enRespuestaA.authorId(), enRespuestaA.authorName(), enRespuestaA.id(),
                                    clientId, ChatMessageKind.ANSWER, text, reloj.instant(), null))
                    .message();
        }
        String clientId = PREFIJO_SISTEMA + UUID.randomUUID();
        return agregar(projectId, clientId, null,
                        seq -> new ChatMessage(
                                UUID.randomUUID().toString(), proyecto, null, seq,
                                ChatAuthorType.SYSTEM, null, NOMBRE_SISTEMA, null,
                                null, null, null,
                                clientId, ChatMessageKind.SYSTEM, text, reloj.instant(), null))
                .message();
    }

    // ------------------------------------------------------------- escritura

    /**
     * seq → guardar → difundir, bajo el candado del proyecto. Separado del armado del mensaje
     * para que la IA y el sistema escriban por el mismo camino y con las mismas garantías.
     */
    private ChatPostResult agregar(
            UUID projectId, String clientMessageId, String autorId, LongFunction<ChatMessage> armar) {
        String proyecto = projectId.toString();
        ReentrantLock candado = candados.computeIfAbsent(projectId, p -> new ReentrantLock());
        candado.lock();
        try {
            // Segunda mirada, ya con el candado: dos reintentos simultáneos pasaron los dos la
            // primera, y sólo uno puede llegar hasta acá sin encontrar al otro.
            Optional<ChatPostResult> repetido = duplicado(proyecto, clientMessageId, autorId);
            if (repetido.isPresent()) {
                return repetido.get();
            }
            ChatMessage guardado;
            try {
                guardado = store.save(armar.apply(store.nextSeq(proyecto)));
            } catch (DuplicateKeyException e) {
                // Sólo con dos instancias del backend, cuyo candado no se comparte. Gana el
                // índice único; el número consumido queda como hueco, que es el mal menor.
                return duplicado(proyecto, clientMessageId, autorId).orElseThrow(() -> e);
            }
            difundir(guardado);
            return new ChatPostResult(guardado, false);
        } finally {
            candado.unlock();
        }
    }

    private void difundir(ChatMessage mensaje) {
        try {
            publisher.publishMessage(mensaje);
        } catch (RuntimeException e) {
            // Ya está guardado: quien no lo vio en vivo lo recupera del historial. Fallar acá
            // haría que el cliente reintente y reciba "duplicado" de algo que sí se aceptó.
            log.warn("No se pudo difundir el mensaje {} del chat: {}", mensaje.id(), e.getMessage());
        }
    }

    private void avisar(ChatMessage mensaje) {
        for (UserMessageListener listener : listeners) {
            try {
                listener.onUserMessage(mensaje);
            } catch (RuntimeException e) {
                // Que la IA falle no puede hacer fallar el mensaje de la persona.
                log.warn("El listener {} falló con el mensaje {}", listener, mensaje.id(), e);
            }
        }
    }

    // ------------------------------------------------------------ validación

    private Optional<ChatPostResult> duplicado(
            String proyecto, String clientMessageId, String autorId) {
        return store.findByClientMessageId(proyecto, clientMessageId)
                .map(
                        original -> {
                            if (!java.util.Objects.equals(autorId, original.authorId())) {
                                // El id lo eligió otro cliente para otro mensaje. Devolverlo
                                // le mostraría a esta persona un mensaje ajeno como si fuera
                                // suyo; es un pedido mal formado, no un reintento.
                                throw new ChatRejectedException(
                                        ChatErrorCode.INVALID_PAYLOAD,
                                        "clientMessageId ya usado por otro mensaje");
                            }
                            return new ChatPostResult(original, true);
                        });
    }

    private static UUID usuarioDe(Principal principal) {
        if (principal == null) {
            throw new ChatRejectedException(
                    ChatErrorCode.NOT_PROJECT_MEMBER, "No sos miembro de este proyecto");
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new ChatRejectedException(
                    ChatErrorCode.NOT_PROJECT_MEMBER, "No sos miembro de este proyecto");
        }
    }

    private static String validarTexto(ChatPostRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "El mensaje no puede estar vacío");
        }
        String texto = request.text().strip();
        // Por code points y no por chars: un emoji son dos chars, y quien escribe no tiene
        // forma de saberlo.
        if (texto.codePointCount(0, texto.length()) > LARGO_MAXIMO) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD,
                    "El mensaje supera los " + LARGO_MAXIMO + " caracteres");
        }
        return texto;
    }

    private static String validarClientMessageId(String clientMessageId) {
        if (clientMessageId == null
                || clientMessageId.isBlank()
                || clientMessageId.length() > LARGO_MAXIMO_CLIENT_ID) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD,
                    "clientMessageId es obligatorio y de hasta "
                            + LARGO_MAXIMO_CLIENT_ID + " caracteres");
        }
        // Reservados para la IA y el sistema: si una persona pudiera usar "ai-{id}", le quitaría
        // a esa pregunta su respuesta (postFromAi lo vería como un duplicado ajeno).
        if (clientMessageId.startsWith(PREFIJO_IA) || clientMessageId.startsWith(PREFIJO_SISTEMA)) {
            throw new ChatRejectedException(
                    ChatErrorCode.INVALID_PAYLOAD, "clientMessageId usa un prefijo reservado");
        }
        return clientMessageId;
    }

    private Participante autor(UUID userId, Participante deLaSesion) {
        if (deLaSesion != null && userId.equals(deLaSesion.userId())) {
            return deLaSesion;
        }
        String nombre =
                participants
                        .fullName(userId)
                        .orElseThrow(
                                () ->
                                        new ChatRejectedException(
                                                ChatErrorCode.NOT_PROJECT_MEMBER,
                                                "No sos miembro de este proyecto"));
        // La misma función que colorea cursores y chips de presencia: el historial conserva el
        // color con el que esa persona aparece en el lienzo.
        return Participante.de(userId, nombre);
    }
}
