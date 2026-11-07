package com.modelcollab.assistant.ai;

import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.core.ChatStore;
import com.modelcollab.assistant.core.SlidingWindowRateLimiter;
import com.modelcollab.assistant.core.UserMessageListener;
import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import com.modelcollab.metamodel.service.MetamodelValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * La IA del chat (CU19): recibe cada mensaje de una persona y decide si responde.
 *
 * <h2>Cuándo</h2>
 *
 * <ul>
 *   <li>Sólo mensajes {@code USER}: lo que escriben la IA y el sistema nunca dispara nada (además,
 *       {@code ChatService.postFromAi} no avisa a los listeners).
 *   <li>{@code OFF}: nunca. {@code ALWAYS} y {@code MENTOR_PROACTIVE}: siempre.
 *   <li>{@code ON_MENTION}: si el texto menciona {@code @IA} como palabra (sin distinguir
 *       mayúsculas; no dentro de otra, como en "correo@iaas"), o si responde ({@code replyToId}) a
 *       un mensaje de la IA.
 * </ul>
 *
 * <h2>Cómo</h2>
 *
 * <ul>
 *   <li>Una respuesta a la vez <b>por proyecto</b>, en orden de llegada: una cola por proyecto con
 *       hasta {@value #MAX_PENDIENTES} pendientes y una sola tarea que la vacía. Si se desborda, un
 *       aviso del sistema y esa pregunta se descarta.
 *   <li>Todo en el {@link AssistantExecutor} (hilos virtuales): {@link #onUserMessage} vuelve
 *       enseguida, sin bloquear el hilo de STOMP ni la petición REST.
 *   <li>Secuencia: AI_TYPING on → últimos {@value #VENTANA} mensajes → resumen del diagrama del
 *       mensaje, si trae {@code diagramId} y es del proyecto ({@link DiagramContextSource} y
 *       {@link DiagramDigest}; si falla, sin resumen) → {@link
 *       ConversationContextBuilder} → modelo (tope de 30 s) → {@link AddressingGuard} → {@code
 *       postFromAi} → AI_TYPING off, <b>siempre</b>, también si algo falla.
 *   <li>Hasta {@value #LLAMADAS_POR_MINUTO} llamadas al modelo por minuto y proyecto; al
 *       superarlo, un aviso como máximo una vez por minuto.
 *   <li>Los fallos se le cuentan al chat con un mensaje del sistema genérico. En el log sólo van
 *       {@code cliente.descripcion()} y el tipo de excepción: nunca la clave, el detalle del
 *       proveedor ni el cuerpo de la respuesta.
 * </ul>
 */
@Component
public class AssistantOrchestrator implements UserMessageListener {

    private static final Logger log = LoggerFactory.getLogger(AssistantOrchestrator.class);

    public static final int MAX_PENDIENTES = 5;
    public static final int VENTANA = 100;
    public static final int LLAMADAS_POR_MINUTO = 10;
    public static final Duration TOPE_DEL_MODELO = Duration.ofSeconds(30);

    public static final String NO_DISPONIBLE = "La IA no está disponible ahora";
    public static final String SIN_CUOTA = "La IA alcanzó su límite de uso; intenta más tarde";
    public static final String OCUPADA = "La IA está ocupada, intenta de nuevo en unos segundos";
    public static final String MUCHAS_CONSULTAS = "La IA está recibiendo muchas consultas; espera un momento";

    /** "@IA" o "@Senior" como palabra, o "Senior," / "Senior:" al dirigirse al asistente. */
    static final Pattern MENCION = Pattern.compile(
            "(?<![\\p{L}\\p{N}_@])@(?:ia|senior)(?![\\p{L}\\p{N}_])|(?<![\\p{L}\\p{N}_@])senior\\s*[,:]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final ChatService chatService;
    private final ChatStore store;
    private final ChatPublisher publisher;
    private final AiChatModelClient cliente;
    private final AssistantSettingsProvider ajustes;
    private final AssistantExecutor ejecutor;
    private final DiagramContextSource diagramas;
    private final Clock reloj;
    private final Duration tope;
    private final ConversationContextBuilder contexto = new ConversationContextBuilder();
    private final SlidingWindowRateLimiter<UUID> limite;
    private final Map<UUID, Cola> colas = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> ultimoAvisoDeLimite = new ConcurrentHashMap<>();

    private record Pendiente(ChatMessage pregunta, AssistantSettings ajustes) {}

    /** La cola de un proyecto. Todo acceso, bajo su propio monitor. */
    private static final class Cola {
        final ArrayDeque<Pendiente> pendientes = new ArrayDeque<>();
        boolean corriendo;
    }

    /**
     * {@code @Lazy} en ChatService rompe un ciclo real: ChatService recibe la lista de
     * UserMessageListener, que incluye a este orquestador, y éste necesita a ChatService para
     * escribir la respuesta.
     */
    @Autowired
    public AssistantOrchestrator(
            @Lazy ChatService chatService,
            ChatStore store,
            ChatPublisher publisher,
            AiChatModelClient cliente,
            AssistantSettingsProvider ajustes,
            AssistantExecutor ejecutor,
            DiagramContextSource diagramas) {
        this(chatService, store, publisher, cliente, ajustes, ejecutor, diagramas,
                Clock.systemUTC(), TOPE_DEL_MODELO);
    }

    /** Con reloj y tope inyectables, para los tests. */
    public AssistantOrchestrator(
            ChatService chatService,
            ChatStore store,
            ChatPublisher publisher,
            AiChatModelClient cliente,
            AssistantSettingsProvider ajustes,
            AssistantExecutor ejecutor,
            DiagramContextSource diagramas,
            Clock reloj,
            Duration tope) {
        this.chatService = chatService;
        this.store = store;
        this.publisher = publisher;
        this.cliente = cliente;
        this.ajustes = ajustes;
        this.ejecutor = ejecutor;
        this.diagramas = diagramas == null ? DiagramContextSource.ninguno() : diagramas;
        this.reloj = reloj;
        this.tope = tope;
        this.limite = new SlidingWindowRateLimiter<>(LLAMADAS_POR_MINUTO, Duration.ofMinutes(1), reloj);
    }

    // --------------------------------------------------------------- entrada

    @Override
    public void onUserMessage(ChatMessage mensaje) {
        if (mensaje == null || mensaje.authorType() != ChatAuthorType.USER) {
            return;
        }
        UUID proyecto;
        try {
            proyecto = UUID.fromString(mensaje.projectId());
        } catch (RuntimeException e) {
            return;
        }
        AssistantSettings conf = ajustes.forProject(proyecto);
        if (conf == null) {
            conf = AssistantSettings.DEFAULTS;
        }
        if (!debeResponder(mensaje, conf.mode())) {
            return;
        }
        encolar(proyecto, new Pendiente(mensaje, conf));
    }

    boolean debeResponder(ChatMessage mensaje, AssistantMode modo) {
        return switch (modo) {
            case OFF -> false;
            case ALWAYS, MENTOR_PROACTIVE -> true;
            case ON_MENTION -> menciona(mensaje.text()) || respondeALaIa(mensaje);
        };
    }

    static boolean menciona(String texto) {
        return texto != null && MENCION.matcher(texto).find();
    }

    private boolean respondeALaIa(ChatMessage mensaje) {
        if (mensaje.replyToId() == null) {
            return false;
        }
        return store.findById(mensaje.projectId(), mensaje.replyToId())
                .map(original -> original.authorType() == ChatAuthorType.AI)
                .orElse(false);
    }

    // ------------------------------------------------------- cola por proyecto

    private void encolar(UUID proyecto, Pendiente pendiente) {
        Cola cola = colas.computeIfAbsent(proyecto, p -> new Cola());
        boolean arrancar = false;
        boolean desborde = false;
        synchronized (cola) {
            if (cola.pendientes.size() >= MAX_PENDIENTES) {
                desborde = true;
            } else {
                cola.pendientes.addLast(pendiente);
                if (!cola.corriendo) {
                    cola.corriendo = true;
                    arrancar = true;
                }
            }
        }
        // Fuera del monitor: con el ejecutor síncrono de los tests la tarea corre acá mismo.
        if (desborde) {
            sistema(proyecto, OCUPADA);
        } else if (arrancar) {
            try {
                ejecutor.execute(() -> vaciar(proyecto, cola));
            } catch (RuntimeException e) {
                // El ejecutor está cerrado (apagado de la aplicación): no queda nadie para
                // atender la cola.
                synchronized (cola) {
                    cola.pendientes.clear();
                    cola.corriendo = false;
                }
                log.warn("La IA del chat no pudo programar la respuesta: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** La única tarea viva por proyecto: atiende de a una, en orden, hasta vaciar la cola. */
    private void vaciar(UUID proyecto, Cola cola) {
        boolean terminoBien = false;
        try {
            while (true) {
                Pendiente siguiente;
                synchronized (cola) {
                    siguiente = cola.pendientes.pollFirst();
                    if (siguiente == null) {
                        cola.corriendo = false;
                        terminoBien = true;
                        return;
                    }
                }
                try {
                    responder(proyecto, siguiente);
                } catch (RuntimeException e) {
                    // responder() ya atrapa lo suyo; esto es la última red para no dejar la
                    // cola sin nadie que la atienda.
                    log.error("Fallo inesperado de la IA del chat: {}", e.getClass().getSimpleName());
                }
            }
        } finally {
            if (!terminoBien) {
                synchronized (cola) {
                    cola.corriendo = false;
                }
            }
        }
    }

    // ------------------------------------------------------------- respuesta

    private void responder(UUID proyecto, Pendiente pendiente) {
        ChatMessage pregunta = pendiente.pregunta();
        if (!limite.tryAcquire(proyecto)) {
            avisarLimite(proyecto);
            return;
        }
        escribiendo(proyecto, pregunta, true);
        try {
            List<ChatMessage> historial = store.latest(proyecto.toString(), VENTANA);
            ChatRequest pedido = contexto.build(
                    pregunta, historial, pendiente.ajustes(), resumenDelDiagrama(proyecto, pregunta));
            ChatCompletion respuesta = llamarConTope(pedido);
            String texto = AddressingGuard.apply(respuesta.text(), pedido.askerFirstName());
            chatService.postFromAi(proyecto, ChatAuthorType.AI, texto, pregunta);
        } catch (AiUnavailableException e) {
            log.warn("{} no respondió en el chat: {} ({})",
                    cliente.descripcion(), e.getClass().getSimpleName(), e.reason());
            sistema(proyecto, e.reason() == AiUnavailableException.Reason.PROVIDER_QUOTA ? SIN_CUOTA : NO_DISPONIBLE);
        } catch (TimeoutException e) {
            log.warn("{} no respondió en el chat en {} s: {}",
                    cliente.descripcion(), tope.toSeconds(), e.getClass().getSimpleName());
            sistema(proyecto, NO_DISPONIBLE);
        } catch (RuntimeException e) {
            log.error("Fallo inesperado respondiendo en el chat con {}: {}",
                    cliente.descripcion(), e.getClass().getSimpleName());
            sistema(proyecto, NO_DISPONIBLE);
        } finally {
            escribiendo(proyecto, pregunta, false);
        }
    }

    /**
     * El digest del diagrama del mensaje, o {@code null}: sin {@code diagramId}, si no existe, es
     * de otro proyecto o falla la carga. Nunca lanza; el log no lleva ids ni contenido.
     */
    String resumenDelDiagrama(UUID proyecto, ChatMessage pregunta) {
        String id = pregunta.diagramId();
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return diagramas.cargar(UUID.fromString(id), proyecto)
                    .map(AssistantOrchestrator::resumenConValidacion)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Chat: no se pudo armar el resumen del diagrama; se responde sin él ({})",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    static String resumenConValidacion(CanonicalModel modelo) {
        String base = DiagramDigest.of(modelo);
        if (modelo == null || modelo.classes().isEmpty()) {
            return base;
        }
        ValidationReportDTO reporte = new MetamodelValidator(new IdentifierSanitizer()).validar(modelo);
        boolean tieneDetalle = !modelo.relationships().isEmpty()
                || modelo.classes().stream().anyMatch(c ->
                        c != null && (!c.attributes().isEmpty() || !c.methods().isEmpty()));
        List<ValidationReportDTO.ValidationIssue> errores = reporte.errors();
        List<ValidationReportDTO.ValidationIssue> avisos = tieneDetalle ? reporte.warnings() : List.of();
        if (errores.isEmpty() && avisos.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        if (!errores.isEmpty()) {
            sb.append("\nErrores del validador:");
            for (ValidationReportDTO.ValidationIssue err : errores) {
                String linea = "\n- " + ConversationContextBuilder.neutralizar(err.message());
                if (sb.length() + linea.length() > DiagramDigest.TOPE) {
                    break;
                }
                sb.append(linea);
            }
        }
        if (!avisos.isEmpty()) {
            String encabezado = "\nAvisos del validador:";
            if (sb.length() + encabezado.length() <= DiagramDigest.TOPE) {
                sb.append(encabezado);
                for (ValidationReportDTO.ValidationIssue warn : avisos) {
                    String linea = "\n- " + ConversationContextBuilder.neutralizar(warn.message());
                    if (sb.length() + linea.length() > DiagramDigest.TOPE) {
                        break;
                    }
                    sb.append(linea);
                }
            }
        }
        return sb.toString();
    }

    /**
     * El modelo corre en otra tarea del mismo ejecutor y acá se espera hasta {@code tope}. Si se
     * pasa, la respuesta tardía se descarta: nadie la lee.
     */
    private ChatCompletion llamarConTope(ChatRequest pedido) throws TimeoutException {
        CompletableFuture<ChatCompletion> futuro = new CompletableFuture<>();
        ejecutor.execute(() -> {
            try {
                futuro.complete(cliente.chat(pedido));
            } catch (Throwable t) {
                futuro.completeExceptionally(t);
            }
        });
        try {
            return futuro.get(tope.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException(AiUnavailableException.Reason.UNAVAILABLE, NO_DISPONIBLE, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AiUnavailableException propia) {
                throw propia;
            }
            throw new AiUnavailableException(AiUnavailableException.Reason.UNAVAILABLE, NO_DISPONIBLE, e.getCause());
        } finally {
            futuro.cancel(false);
        }
    }

    // ------------------------------------------------------------------ avisos

    private void avisarLimite(UUID proyecto) {
        Instant ahora = reloj.instant();
        boolean[] toca = {false};
        ultimoAvisoDeLimite.compute(proyecto, (p, ultimo) -> {
            if (ultimo == null || !ahora.isBefore(ultimo.plus(Duration.ofMinutes(1)))) {
                toca[0] = true;
                return ahora;
            }
            return ultimo;
        });
        if (toca[0]) {
            sistema(proyecto, MUCHAS_CONSULTAS);
        }
    }

    private void sistema(UUID proyecto, String texto) {
        try {
            chatService.postFromAi(proyecto, ChatAuthorType.SYSTEM, texto, null);
        } catch (RuntimeException e) {
            log.warn("No se pudo escribir el aviso del sistema en el chat: {}", e.getClass().getSimpleName());
        }
    }

    private void escribiendo(UUID proyecto, ChatMessage pregunta, boolean activo) {
        try {
            publisher.publishTyping(proyecto.toString(), pregunta.authorId(), pregunta.authorName(), activo);
        } catch (RuntimeException e) {
            log.debug("No se pudo difundir AI_TYPING={}: {}", activo, e.getClass().getSimpleName());
        }
    }
}
