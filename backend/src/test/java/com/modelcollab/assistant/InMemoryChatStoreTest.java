package com.modelcollab.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.store.InMemoryChatStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class InMemoryChatStoreTest {

    private static final String PROYECTO = "p-1";

    private final InMemoryChatStore store = new InMemoryChatStore();

    static ChatMessage mensaje(String proyecto, long seq, String clientMessageId) {
        return new ChatMessage(
                "id-" + proyecto + "-" + seq, proyecto, null, seq, ChatAuthorType.USER, "u", "Ana",
                "#D55E00", null, null, null, clientMessageId, ChatMessageKind.CHAT, "hola " + seq,
                Instant.EPOCH, null);
    }

    @Test
    @DisplayName("nextSeq concurrente: cada número sale una sola vez y no quedan huecos")
    void nextSeqAtomico() throws Exception {
        Set<Long> vistos = ConcurrentHashMap.newKeySet();
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        List<Future<?>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tareas.add(hilos.submit(() -> {
                for (int j = 0; j < 250; j++) {
                    assertThat(vistos.add(store.nextSeq(PROYECTO))).isTrue();
                }
            }));
        }
        for (Future<?> t : tareas) {
            t.get();
        }
        hilos.shutdown();

        assertThat(vistos).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, 2000).boxed().toList());
    }

    @Test
    @DisplayName("Cada proyecto tiene su propia secuencia")
    void secuenciaPorProyecto() {
        assertThat(store.nextSeq("a")).isEqualTo(1);
        assertThat(store.nextSeq("a")).isEqualTo(2);
        assertThat(store.nextSeq("b")).isEqualTo(1);
    }

    @Test
    @DisplayName("(projectId, clientMessageId) es único, pero el mismo id en otro proyecto no choca")
    void unicidadPorProyecto() {
        store.save(mensaje(PROYECTO, 1, "c-1"));

        assertThatThrownBy(() -> store.save(mensaje(PROYECTO, 2, "c-1")))
                .isInstanceOf(DuplicateKeyException.class);
        store.save(mensaje("otro", 1, "c-1"));
        assertThat(store.findByClientMessageId(PROYECTO, "c-1")).map(ChatMessage::seq).contains(1L);
    }

    @Test
    @DisplayName("latest, before y after devuelven siempre en orden ascendente por seq")
    void historialAscendente() {
        // Se guardan desordenados a propósito: es lo que pasa con escritores concurrentes.
        for (long seq : new long[] {3, 1, 5, 2, 4}) {
            store.save(mensaje(PROYECTO, seq, "c-" + seq));
        }

        assertThat(store.latest(PROYECTO, 3)).extracting(ChatMessage::seq).containsExactly(3L, 4L, 5L);
        assertThat(store.before(PROYECTO, 4, 2)).extracting(ChatMessage::seq).containsExactly(2L, 3L);
        assertThat(store.after(PROYECTO, 2, 2)).extracting(ChatMessage::seq).containsExactly(3L, 4L);
        assertThat(store.latest("vacio", 10)).isEmpty();
    }

    @Test
    @DisplayName("findById no encuentra un mensaje de otro proyecto")
    void findByIdAcotadoAlProyecto() {
        ChatMessage m = store.save(mensaje(PROYECTO, 1, "c-1"));

        assertThat(store.findById(PROYECTO, m.id())).contains(m);
        assertThat(store.findById("otro", m.id())).isEmpty();
    }
}
