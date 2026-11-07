package com.modelcollab.assistant.summary;

import com.modelcollab.assistant.ai.AssistantSettings;
import com.modelcollab.assistant.ai.AssistantSettingsProvider;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.UserMessageListener;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Disparador automático de resúmenes de conversación (CU24).
 *
 * <p>Supervisa la llegada de mensajes (implementando {@link UserMessageListener}) para disparar
 * el resumen cuando se alcanza la frecuencia configurada (p. ej. cada 30 mensajes), y corre un
 * barrido programado periódico para resumir tras periodos de inactividad.
 */
@Service
public class ChatSummaryTriggerService implements UserMessageListener {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryTriggerService.class);

    private static final Duration UMBRAL_INACTIVIDAD = Duration.ofMinutes(10);
    private static final int MIN_MENSAJES_INACTIVIDAD = 5;

    private final ChatSummaryService summaryService;
    private final AssistantSettingsProvider settingsProvider;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-summary-trigger");
        t.setDaemon(true);
        return t;
    });

    /** Rastreo de último seq y última actividad por proyecto. */
    private final ConcurrentHashMap<UUID, Long> ultimoSeqResumido = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> ultimoSeqVisto = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Instant> ultimaActividad = new ConcurrentHashMap<>();

    public ChatSummaryTriggerService(
            ChatSummaryService summaryService,
            AssistantSettingsProvider settingsProvider) {
        this.summaryService = summaryService;
        this.settingsProvider = settingsProvider;
    }

    @Override
    public void onUserMessage(ChatMessage message) {
        if (message == null || message.projectId() == null) return;

        try {
            UUID projectId = UUID.fromString(message.projectId());
            long seq = message.seq();
            ultimoSeqVisto.put(projectId, seq);
            ultimaActividad.put(projectId, Instant.now());

            AssistantSettings settings = settingsProvider.forProject(projectId);
            int frecuencia = settings != null && settings.summaryFrequency() > 0
                    ? settings.summaryFrequency()
                    : AssistantSettings.DEFAULT_SUMMARY_FREQUENCY;

            long baseSeq = ultimoSeqResumido.getOrDefault(projectId, 0L);
            if (seq - baseSeq >= frecuencia) {
                log.info("Disparando resumen por frecuencia para el proyecto {} (seq={}, base={}, frec={})",
                        projectId, seq, baseSeq, frecuencia);
                ultimoSeqResumido.put(projectId, seq);
                executor.submit(() -> summaryService.summarize(projectId, ChatSummaryTrigger.FREQUENCY));
            }
        } catch (Exception e) {
            log.warn("Error en ChatSummaryTriggerService al procesar mensaje: {}", e.getMessage());
        }
    }

    /**
     * Barrido programado único para detectar inactividad en proyectos activos (párrafo 257).
     */
    @Scheduled(fixedDelay = 60000)
    public void barridoPorInactividad() {
        Instant ahora = Instant.now();
        for (var entry : ultimaActividad.entrySet()) {
            UUID projectId = entry.getKey();
            Instant ultimoMomento = entry.getValue();

            if (Duration.between(ultimoMomento, ahora).compareTo(UMBRAL_INACTIVIDAD) >= 0) {
                long visto = ultimoSeqVisto.getOrDefault(projectId, 0L);
                long resumido = ultimoSeqResumido.getOrDefault(projectId, 0L);

                if (visto - resumido >= MIN_MENSAJES_INACTIVIDAD) {
                    log.info("Disparando resumen por inactividad para el proyecto {} ({} mensajes sin resumir)",
                            projectId, (visto - resumido));
                    ultimoSeqResumido.put(projectId, visto);
                    // Actualizamos la marca para no redisparar continuamente en bucle
                    ultimaActividad.put(projectId, ahora);
                    executor.submit(() -> summaryService.summarize(projectId, ChatSummaryTrigger.INACTIVITY));
                }
            }
        }
    }
}
