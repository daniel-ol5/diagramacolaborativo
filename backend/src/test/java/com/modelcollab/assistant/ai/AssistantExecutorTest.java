package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU19, etapa 4, entrega 3a: ejecutor de la IA. */
class AssistantExecutorTest {

    @Test
    @DisplayName("Por defecto, cada tarea corre en un hilo virtual; cerrar() lo apaga")
    void hilosVirtuales() throws Exception {
        AssistantExecutor ejecutor = new AssistantExecutor();
        CompletableFuture<Boolean> esVirtual = new CompletableFuture<>();

        ejecutor.execute(() -> esVirtual.complete(Thread.currentThread().isVirtual()));

        assertThat(esVirtual.get(5, TimeUnit.SECONDS)).isTrue();
        ejecutor.cerrar();
        ejecutor.cerrar(); // idempotente
    }

    @Test
    @DisplayName("sincrono(): la tarea corre en el hilo de quien la envía")
    void sincrono() {
        AtomicReference<Thread> donde = new AtomicReference<>();
        AssistantExecutor ejecutor = AssistantExecutor.sincrono();

        ejecutor.execute(() -> donde.set(Thread.currentThread()));

        assertThat(donde.get()).isSameAs(Thread.currentThread());
        ejecutor.cerrar(); // no hace nada
    }
}
