package com.modelcollab.assistant.summary;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.modelcollab.assistant.ai.AiChatModelClient;
import com.modelcollab.assistant.ai.ChatRequest;
import com.modelcollab.assistant.ai.ChatTurn;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Servicio encargado de generar y gestionar resúmenes automáticos y manuales de conversación (CU24).
 *
 * <p>Condensa el chat en decisiones y preguntas abiertas, publica tarjetas estructuradas en el chat
 * y alimenta al orquestador de IA para mantener contexto sin saturar la ventana de tokens.
 */
@Service
public class ChatSummaryService implements ChatSummaryProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryService.class);

    /** Mapper permisivo para interpretar las respuestas JSON de la IA (párrafo 257). */
    private static final ObjectMapper PERMISSIVE_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .build();

    private final ChatStore chatStore;
    private final ChatService chatService;
    private final AiChatModelClient aiClient;
    private final com.modelcollab.assistant.ai.AssistantSettingsProvider settingsProvider;

    /** Banderas para resumir una sola vez a la vez por proyecto (párrafo 252). */
    private final ConcurrentHashMap<UUID, AtomicBoolean> resumenEnProgreso = new ConcurrentHashMap<>();

    /** Contador de resúmenes consecutivos para forzar regeneración desde cero cada ~10 (párrafo 257). */
    private final ConcurrentHashMap<UUID, AtomicInteger> resumenesAcumulados = new ConcurrentHashMap<>();

    /** Caché en memoria del último resumen por proyecto para lecturas rápidas del orquestador. */
    private final ConcurrentHashMap<UUID, ChatSummaryData> ultimoResumenCache = new ConcurrentHashMap<>();

    @Autowired
    public ChatSummaryService(
            ChatStore chatStore,
            @Lazy ChatService chatService,
            AiChatModelClient aiClient,
            @Autowired(required = false)
            com.modelcollab.assistant.ai.AssistantSettingsProvider settingsProvider) {
        this.chatStore = chatStore;
        this.chatService = chatService;
        this.aiClient = aiClient;
        this.settingsProvider = settingsProvider;
    }

    public ChatSummaryService(
            ChatStore chatStore,
            ChatService chatService,
            AiChatModelClient aiClient) {
        this(chatStore, chatService, aiClient, null);
    }

    /**
     * Genera un resumen del chat del proyecto si hay mensajes nuevos por condensar.
     *
     * @param projectId id del proyecto.
     * @param trigger origen del disparo (frecuencia, inactividad o manual).
     * @return el resumen generado o el existente si no había nuevos mensajes.
     */
    public Optional<ChatSummaryResponse> summarize(UUID projectId, ChatSummaryTrigger trigger) {
        if (projectId == null) {
            return Optional.empty();
        }

        AtomicBoolean ocupado = resumenEnProgreso.computeIfAbsent(projectId, p -> new AtomicBoolean(false));
        if (!ocupado.compareAndSet(false, true)) {
            log.info("Resumen en progreso para el proyecto {}: llamada ignorada ({})", projectId, trigger);
            return Optional.empty();
        }

        try {
            return doSummarize(projectId, trigger);
        } finally {
            ocupado.set(false);
        }
    }

    private Optional<ChatSummaryResponse> doSummarize(UUID projectId, ChatSummaryTrigger trigger) {
        String proyectoStr = projectId.toString();
        List<ChatMessage> historial = chatStore.latest(proyectoStr, 200);
        if (historial == null || historial.isEmpty()) {
            return Optional.empty();
        }

        // Buscar el último resumen previo para saber desde qué secuencia resumir
        Optional<ChatMessage> ultimoResumenPrevio = historial.stream()
                .filter(m -> m.kind() == ChatMessageKind.SUMMARY)
                .max(Comparator.comparingLong(ChatMessage::seq));

        long fromSeq = 1;
        ChatSummaryData resumenPrevioData = null;
        if (ultimoResumenPrevio.isPresent()) {
            ChatMessage msgPrevio = ultimoResumenPrevio.get();
            resumenPrevioData = parsearMetaResumen(msgPrevio);
            if (resumenPrevioData != null) {
                fromSeq = resumenPrevioData.toSeq() + 1;
            } else {
                fromSeq = msgPrevio.seq() + 1;
            }
        }

        final long seqMinima = fromSeq;
        List<ChatMessage> mensajesNuevos = historial.stream()
                .filter(m -> m.seq() >= seqMinima)
                .filter(m -> m.authorType() != ChatAuthorType.SYSTEM)
                .filter(m -> m.kind() != ChatMessageKind.SUMMARY && m.kind() != ChatMessageKind.REVIEW)
                .sorted(Comparator.comparingLong(ChatMessage::seq))
                .toList();

        if (mensajesNuevos.isEmpty()) {
            log.debug("No hay mensajes nuevos para resumir en el proyecto {} desde seq {}", projectId, fromSeq);
            return ultimoResumenPrevio.map(this::aRespuesta);
        }

        long toSeq = mensajesNuevos.get(mensajesNuevos.size() - 1).seq();
        long primerSeq = mensajesNuevos.get(0).seq();

        int cuenta = resumenesAcumulados.computeIfAbsent(projectId, p -> new AtomicInteger(0)).incrementAndGet();
        boolean regenerarDesdeCero = (cuenta % 10 == 0);
        if (regenerarDesdeCero) {
            resumenPrevioData = null;
        }

        // Sintetizar con IA o fallback
        ChatSummaryData data = generarSintesis(projectId, primerSeq, toSeq, mensajesNuevos, resumenPrevioData);

        // Publicar la tarjeta de resumen en el chat
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "CHAT_SUMMARY");
        meta.put("fromSeq", data.fromSeq());
        meta.put("toSeq", data.toSeq());
        meta.put("totalMessages", data.totalMessages());
        meta.put("decisions", data.decisions());
        meta.put("openQuestions", data.openQuestions());

        ChatMessage publicado = chatService.postCard(
                projectId,
                null,
                ChatMessageKind.SUMMARY,
                data.plainText(),
                meta);

        ultimoResumenCache.put(projectId, data);

        return Optional.of(aRespuesta(publicado));
    }

    private ChatSummaryData generarSintesis(
            UUID projectId,
            long fromSeq,
            long toSeq,
            List<ChatMessage> mensajes,
            ChatSummaryData previo) {
        StringBuilder conversacion = new StringBuilder();
        for (ChatMessage m : mensajes) {
            conversacion.append("[").append(m.authorName()).append("]: ").append(m.text()).append("\n");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                Eres el asistente de síntesis en ModelCollab. Tu labor es condensar las conversaciones \
                del equipo de desarrollo en dos listas concretas:
                1. 'decisiones': Acuerdos técnicos, decisiones de diseño UML/ER, atributos, clases o reglas acordadas.
                2. 'preguntasAbiertas': Dudas pendientes, temas no resueltos o preguntas sin contestar.

                Reglas:
                - Responde ÚNICAMENTE un JSON válido con este formato exacto:
                {"decisiones": ["..."], "preguntasAbiertas": ["..."]}
                - Sé conciso, claro y directo.
                """);

        if (previo != null) {
            prompt.append("\nResumen previo acumulado (actualízalo incorporando lo nuevo):\n");
            prompt.append("Decisiones previas: ").append(String.join("; ", previo.decisions())).append("\n");
            prompt.append("Preguntas abiertas previas: ").append(String.join("; ", previo.openQuestions())).append("\n");
        }

        List<String> decisiones = new ArrayList<>();
        List<String> preguntasAbiertas = new ArrayList<>();

        try {
            ChatRequest request = new ChatRequest(
                    prompt.toString(),
                    List.of(ChatTurn.user("Mensajes nuevos a resumir:\n" + conversacion)),
                    0.2,
                    450,
                    "Equipo");

            AiChatModelClient clientActivo = (aiClient instanceof com.modelcollab.assistant.ai.AssistantChatModelRouter router && settingsProvider != null && projectId != null)
                    ? router.clientePara(settingsProvider.forProject(projectId))
                    : aiClient;

            var respuesta = clientActivo.chat(request);
            if (respuesta != null && respuesta.text() != null && !respuesta.text().isBlank()
                    && !respuesta.text().contains("(respuesta simulada)")) {
                parsearRespuestaIa(respuesta.text(), decisiones, preguntasAbiertas);
            }
        } catch (Exception e) {
            log.warn("Fallo al consultar IA para resumen, usando síntesis determinista: {}", e.getMessage());
        }

        // Si la IA no produjo listas válidas o estaba simulada, aplicar fallback determinista
        if (decisiones.isEmpty() && preguntasAbiertas.isEmpty()) {
            generarFallback(mensajes, previo, decisiones, preguntasAbiertas);
        }

        String textoPlano = formatearTextoPlano(fromSeq, toSeq, decisiones, preguntasAbiertas);

        return new ChatSummaryData(
                fromSeq,
                toSeq,
                mensajes.size(),
                List.copyOf(decisiones),
                List.copyOf(preguntasAbiertas),
                textoPlano);
    }

    private void parsearRespuestaIa(String texto, List<String> decisiones, List<String> preguntasAbiertas) {
        try {
            String jsonLimpio = texto.trim();
            if (jsonLimpio.startsWith("```")) {
                int firstNewline = jsonLimpio.indexOf('\n');
                int lastBackticks = jsonLimpio.lastIndexOf("```");
                if (firstNewline != -1 && lastBackticks > firstNewline) {
                    jsonLimpio = jsonLimpio.substring(firstNewline + 1, lastBackticks).trim();
                }
            }

            Map<String, Object> map = PERMISSIVE_MAPPER.readValue(jsonLimpio, new TypeReference<>() {});
            if (map.get("decisiones") instanceof List<?> listDec) {
                for (Object d : listDec) {
                    if (d != null && !d.toString().isBlank()) decisiones.add(d.toString().trim());
                }
            }
            if (map.get("preguntasAbiertas") instanceof List<?> listPreg) {
                for (Object p : listPreg) {
                    if (p != null && !p.toString().isBlank()) preguntasAbiertas.add(p.toString().trim());
                }
            }
        } catch (Exception e) {
            log.debug("No se pudo parsear el JSON de la IA para el resumen: {}", e.getMessage());
        }
    }

    private void generarFallback(
            List<ChatMessage> mensajes,
            ChatSummaryData previo,
            List<String> decisiones,
            List<String> preguntasAbiertas) {
        if (previo != null) {
            decisiones.addAll(previo.decisions());
            preguntasAbiertas.addAll(previo.openQuestions());
        }

        for (ChatMessage m : mensajes) {
            String txt = m.text().trim();
            if (txt.endsWith("?") || txt.contains("¿")) {
                if (preguntasAbiertas.size() < 4) {
                    preguntasAbiertas.add(txt);
                }
            } else if (txt.toLowerCase().contains("acuerdo") || txt.toLowerCase().contains("queda")
                    || txt.toLowerCase().contains("clase") || txt.toLowerCase().contains("agreg")
                    || txt.toLowerCase().contains("decid")) {
                if (decisiones.size() < 4) {
                    decisiones.add(m.authorName() + ": " + txt);
                }
            }
        }

        if (decisiones.isEmpty()) {
            decisiones.add("Conversación general sobre el diseño del proyecto.");
        }
    }

    private String formatearTextoPlano(
            long fromSeq,
            long toSeq,
            List<String> decisiones,
            List<String> preguntasAbiertas) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📝 Resumen de la conversación (mensajes %d–%d)\n\n", fromSeq, toSeq));

        sb.append("Decisiones y acuerdos:\n");
        if (decisiones.isEmpty()) {
            sb.append("• Sin decisiones registradas aún.\n");
        } else {
            for (String d : decisiones) {
                sb.append("• ").append(d).append("\n");
            }
        }

        sb.append("\nPreguntas abiertas / pendientes:\n");
        if (preguntasAbiertas.isEmpty()) {
            sb.append("• No hay dudas pendientes por resolver.\n");
        } else {
            for (String q : preguntasAbiertas) {
                sb.append("• ").append(q).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private ChatSummaryData parsearMetaResumen(ChatMessage msg) {
        if (msg == null || msg.meta() == null) return null;
        try {
            Map<String, Object> meta = msg.meta();
            long fromSeq = meta.containsKey("fromSeq") ? ((Number) meta.get("fromSeq")).longValue() : 1;
            long toSeq = meta.containsKey("toSeq") ? ((Number) meta.get("toSeq")).longValue() : msg.seq();
            int total = meta.containsKey("totalMessages") ? ((Number) meta.get("totalMessages")).intValue() : 0;

            List<String> dec = new ArrayList<>();
            if (meta.get("decisiones") instanceof List<?> list) {
                list.forEach(item -> dec.add(item.toString()));
            } else if (meta.get("decisions") instanceof List<?> list) {
                list.forEach(item -> dec.add(item.toString()));
            }

            List<String> preg = new ArrayList<>();
            if (meta.get("preguntasAbiertas") instanceof List<?> list) {
                list.forEach(item -> preg.add(item.toString()));
            } else if (meta.get("openQuestions") instanceof List<?> list) {
                list.forEach(item -> preg.add(item.toString()));
            }

            return new ChatSummaryData(fromSeq, toSeq, total, dec, preg, msg.text());
        } catch (Exception e) {
            return null;
        }
    }

    private ChatSummaryResponse aRespuesta(ChatMessage msg) {
        ChatSummaryData data = parsearMetaResumen(msg);
        long fromSeq = data != null ? data.fromSeq() : 1;
        long toSeq = data != null ? data.toSeq() : msg.seq();
        int total = data != null ? data.totalMessages() : 0;
        List<String> dec = data != null ? data.decisions() : List.of();
        List<String> preg = data != null ? data.openQuestions() : List.of();

        return new ChatSummaryResponse(
                msg.id(),
                msg.projectId(),
                msg.seq(),
                fromSeq,
                toSeq,
                total,
                dec,
                preg,
                msg.text(),
                msg.createdAt());
    }

    /**
     * Devuelve el último resumen registrado en el chat en formato DTO.
     */
    public Optional<ChatSummaryResponse> getLatestSummary(UUID projectId) {
        if (projectId == null) return Optional.empty();
        List<ChatMessage> recientes = chatStore.latest(projectId.toString(), 100);
        return recientes.stream()
                .filter(m -> m.kind() == ChatMessageKind.SUMMARY)
                .max(Comparator.comparingLong(ChatMessage::seq))
                .map(this::aRespuesta);
    }

    // ---------------------------------------------------------------- ChatSummaryProvider

    @Override
    public Optional<ChatSummaryData> latestSummary(UUID projectId) {
        if (projectId == null) return Optional.empty();
        ChatSummaryData enCache = ultimoResumenCache.get(projectId);
        if (enCache != null) return Optional.of(enCache);

        List<ChatMessage> recientes = chatStore.latest(projectId.toString(), 100);
        return recientes.stream()
                .filter(m -> m.kind() == ChatMessageKind.SUMMARY)
                .max(Comparator.comparingLong(ChatMessage::seq))
                .map(this::parsearMetaResumen);
    }

    @Override
    public Optional<String> latestSummaryText(UUID projectId) {
        return latestSummary(projectId).map(ChatSummaryData::plainText);
    }
}
