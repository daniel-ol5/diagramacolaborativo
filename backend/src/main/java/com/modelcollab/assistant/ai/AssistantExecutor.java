package com.modelcollab.assistant.ai;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ejecutor propio de la IA del chat: un hilo virtual por tarea, para que esperar al modelo no
 * ocupe el hilo de STOMP ni el de la petición REST. Es sólo de este módulo: no habilita los
 * hilos virtuales en el resto de la aplicación ({@code spring.threads.virtual.enabled} sigue sin
 * tocar), ni se mezcla con los planificadores de {@code SchedulingConfig} o {@code WebSocketConfig}.
 *
 * <p>En los tests se sustituye por {@link #sincrono()}, que corre la tarea en el mismo hilo.
 *
 * <p><b>No implementa {@link Executor} a propósito.</b> En Spring Boot 3.5,
 * {@code TaskExecutorConfigurations.TaskExecutorConfiguration} sólo crea el {@code
 * applicationTaskExecutor} si no hay ningún bean de tipo {@code java.util.concurrent.Executor}
 * ({@code @ConditionalOnMissingBean(Executor.class)} en {@code OnExecutorCondition}). Si este
 * componente lo fuera, le quitaría ese ejecutor al resto de la aplicación.
 */
@Component
public class AssistantExecutor {

    private static final Logger log = LoggerFactory.getLogger(AssistantExecutor.class);
    private static final long ESPERA_AL_CERRAR_S = 5;

    private final Executor ejecutor;

    public AssistantExecutor() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    private AssistantExecutor(Executor ejecutor) {
        this.ejecutor = ejecutor;
    }

    /** Corre cada tarea en el hilo de quien la envía. Para tests. */
    public static AssistantExecutor sincrono() {
        return new AssistantExecutor(Runnable::run);
    }

    /** Con cualquier {@link Executor}, por ejemplo uno que retenga las tareas en un test. */
    public static AssistantExecutor con(Executor ejecutor) {
        return new AssistantExecutor(ejecutor);
    }

    public void execute(Runnable tarea) {
        ejecutor.execute(tarea);
    }

    /** Deja terminar lo que está en curso unos segundos; después interrumpe. */
    @PreDestroy
    public void cerrar() {
        if (!(ejecutor instanceof ExecutorService servicio)) {
            return;
        }
        servicio.shutdown();
        try {
            if (!servicio.awaitTermination(ESPERA_AL_CERRAR_S, TimeUnit.SECONDS)) {
                log.warn("La IA del chat tenía respuestas en curso al apagar; se interrumpen");
                servicio.shutdownNow();
            }
        } catch (InterruptedException e) {
            servicio.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
