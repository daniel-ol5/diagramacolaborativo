package com.modelcollab.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.assistant.chat.ChatPostRequest;
import com.modelcollab.assistant.chat.ChatReadState;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.assistant.store.InMemoryChatStore;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.project.model.ProjectRole;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU23, tanda 1: estado de lectura del chat, con InMemoryChatStore. Sin base de datos. */
class ChatReadStateTest {

    private static final UUID PROYECTO = UUID.randomUUID();
    private static final UUID OTRO_PROYECTO = UUID.randomUUID();

    /** Miembros por proyecto. */
    private static final class Participantes implements ChatParticipants {
        final Map<List<UUID>, ProjectRole> roles = new ConcurrentHashMap<>();

        UUID miembro(UUID proyecto, ProjectRole rol) {
            UUID id = UUID.randomUUID();
            roles.put(List.of(proyecto, id), rol);
            return id;
        }

        @Override public Optional<ProjectRole> roleIn(UUID p, UUID u) {
            return Optional.ofNullable(roles.get(List.of(p, u)));
        }
        @Override public Optional<String> fullName(UUID u) { return Optional.of("Persona " + u); }
        @Override public boolean diagramBelongsToProject(UUID d, UUID p) { return false; }
    }

    private static final class Publicador implements ChatPublisher {
        @Override public void publishMessage(ChatMessage m) {}
        @Override public void publishTyping(String p, String u, String n, boolean a) {}
    }

    private InMemoryChatStore store;
    private Participantes participantes;
    private ChatService chat;
    private UUID ana;
    private UUID carlos;
    private UUID vera;

    @BeforeEach
    void preparar() {
        store = new InMemoryChatStore();
        participantes = new Participantes();
        chat = new ChatService(store, new Publicador(), participantes, List.of());
        ana = participantes.miembro(PROYECTO, ProjectRole.EDITOR);
        carlos = participantes.miembro(PROYECTO, ProjectRole.EDITOR);
        vera = participantes.miembro(PROYECTO, ProjectRole.VIEWER);
    }

    private static Principal p(UUID usuario) {
        return new StompPrincipal(usuario);
    }

    private ChatMessage escribir(UUID proyecto, UUID quien, String texto) {
        return chat.postFromUser(proyecto, p(quien),
                new ChatPostRequest(UUID.randomUUID().toString(), texto, null, null)).message();
    }

    private ChatMessage escribir(UUID quien, String texto) {
        return escribir(PROYECTO, quien, texto);
    }

    private static ChatErrorCode codigo(Runnable accion) {
        try {
            accion.run();
        } catch (ChatRejectedException e) {
            return e.code();
        }
        throw new AssertionError("se esperaba ChatRejectedException");
    }

    // ------------------------------------------------------------ readState

    @Test
    @DisplayName("Usuario nuevo en un proyecto sin mensajes: todo en 0")
    void usuarioNuevoSinMensajes() {
        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(0, 0, 0));
    }

    @Test
    @DisplayName("Usuario que nunca marcó: lastReadSeq 0 y todos los mensajes de otros sin leer")
    void usuarioNuevoConMensajes() {
        escribir(carlos, "uno");
        escribir(carlos, "dos");
        escribir(carlos, "tres");

        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(0, 3, 3));
    }

    @Test
    @DisplayName("No cuenta los mensajes propios; los de la IA y del sistema sí")
    void noCuentaLosPropios() {
        ChatMessage pregunta = escribir(ana, "@IA hola");   // 1, de Ana
        escribir(carlos, "hola Ana");                         // 2
        chat.postFromAi(PROYECTO, ChatAuthorType.AI, "Hola Ana", pregunta);        // 3, IA
        chat.postFromAi(PROYECTO, ChatAuthorType.SYSTEM, "La IA no está disponible ahora", null); // 4
        escribir(ana, "gracias");                             // 5, de Ana

        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(0, 3, 5));
        assertThat(chat.readState(PROYECTO, p(carlos))).isEqualTo(new ChatReadState(0, 4, 5));
    }

    @Test
    @DisplayName("Un VIEWER puede leer y marcar su estado")
    void viewerPuede() {
        escribir(ana, "uno");
        escribir(ana, "dos");

        assertThat(chat.readState(PROYECTO, p(vera))).isEqualTo(new ChatReadState(0, 2, 2));
        assertThat(chat.markRead(PROYECTO, p(vera), 1L)).isEqualTo(new ChatReadState(1, 1, 2));
    }

    @Test
    @DisplayName("Quien no es miembro recibe NOT_PROJECT_MEMBER, al leer y al marcar")
    void noMiembro() {
        UUID ajeno = participantes.miembro(OTRO_PROYECTO, ProjectRole.EDITOR);

        assertThat(codigo(() -> chat.readState(PROYECTO, p(ajeno)))).isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigo(() -> chat.markRead(PROYECTO, p(ajeno), 0L))).isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigo(() -> chat.readState(PROYECTO, null))).isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigo(() -> chat.readState(null, p(ana)))).isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
    }

    @Test
    @DisplayName("La membresía se comprueba antes de validar upToSeq")
    void membresiaAntesQueValidacion() {
        UUID ajeno = UUID.randomUUID();
        assertThat(codigo(() -> chat.markRead(PROYECTO, p(ajeno), -1L))).isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
    }

    // ------------------------------------------------------------- markRead

    @Test
    @DisplayName("Marcar avanza lastReadSeq y baja unreadCount")
    void marcarAvanza() {
        for (int i = 1; i <= 5; i++) {
            escribir(carlos, "m" + i);
        }

        assertThat(chat.markRead(PROYECTO, p(ana), 3L)).isEqualTo(new ChatReadState(3, 2, 5));
        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(3, 2, 5));
    }

    @Test
    @DisplayName("Solo avanza: marcar un seq menor no retrocede")
    void soloAvanza() {
        for (int i = 1; i <= 5; i++) {
            escribir(carlos, "m" + i);
        }
        chat.markRead(PROYECTO, p(ana), 4L);

        assertThat(chat.markRead(PROYECTO, p(ana), 2L)).isEqualTo(new ChatReadState(4, 1, 5));
        assertThat(chat.markRead(PROYECTO, p(ana), 0L)).isEqualTo(new ChatReadState(4, 1, 5));
    }

    @Test
    @DisplayName("Se recorta a lastSeq: no se puede marcar un futuro")
    void recortaALastSeq() {
        escribir(carlos, "uno");
        escribir(carlos, "dos");

        assertThat(chat.markRead(PROYECTO, p(ana), 999L)).isEqualTo(new ChatReadState(2, 0, 2));
        // Un mensaje nuevo cuenta como no leído: el 999 no quedó guardado.
        escribir(carlos, "tres");
        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(2, 1, 3));
    }

    @Test
    @DisplayName("Sin mensajes, marcar deja lastReadSeq en 0")
    void marcarSinMensajes() {
        assertThat(chat.markRead(PROYECTO, p(ana), 10L)).isEqualTo(new ChatReadState(0, 0, 0));
    }

    @Test
    @DisplayName("Es idempotente")
    void idempotente() {
        escribir(carlos, "uno");
        escribir(carlos, "dos");
        escribir(carlos, "tres");

        ChatReadState primera = chat.markRead(PROYECTO, p(ana), 2L);
        ChatReadState segunda = chat.markRead(PROYECTO, p(ana), 2L);

        assertThat(segunda).isEqualTo(primera).isEqualTo(new ChatReadState(2, 1, 3));
    }

    @Test
    @DisplayName("upToSeq nulo o negativo: INVALID_PAYLOAD, y el estado no cambia")
    void upToSeqInvalido() {
        escribir(carlos, "uno");
        chat.markRead(PROYECTO, p(ana), 1L);

        assertThat(codigo(() -> chat.markRead(PROYECTO, p(ana), null))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigo(() -> chat.markRead(PROYECTO, p(ana), -1L))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(1, 0, 1));
    }

    @Test
    @DisplayName("El estado es por persona y por proyecto")
    void porPersonaYProyecto() {
        UUID anaEnOtro = ana;
        participantes.roles.put(List.of(OTRO_PROYECTO, anaEnOtro), ProjectRole.EDITOR);
        UUID beto = participantes.miembro(OTRO_PROYECTO, ProjectRole.EDITOR);
        escribir(carlos, "uno");
        escribir(carlos, "dos");
        escribir(OTRO_PROYECTO, beto, "otro uno");

        chat.markRead(PROYECTO, p(ana), 2L);

        assertThat(chat.readState(PROYECTO, p(vera))).isEqualTo(new ChatReadState(0, 2, 2));
        assertThat(chat.readState(OTRO_PROYECTO, p(ana))).isEqualTo(new ChatReadState(0, 1, 1));
    }

    @Test
    @DisplayName("Varios hilos marcando a la vez: queda el máximo, recortado a lastSeq")
    void concurrente() throws Exception {
        for (int i = 1; i <= 10; i++) {
            escribir(carlos, "m" + i);
        }
        int hilos = 16;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ChatReadState>> resultados = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                long hasta = i == 7 ? 50 : i % 9;  // uno pide un futuro; el resto, 0..8
                resultados.add(pool.submit(() -> {
                    largada.await();
                    return chat.markRead(PROYECTO, p(ana), hasta);
                }));
            }
            largada.countDown();
            for (Future<ChatReadState> r : resultados) {
                assertThat(r.get(10, TimeUnit.SECONDS).lastReadSeq()).isBetween(0L, 10L);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(chat.readState(PROYECTO, p(ana))).isEqualTo(new ChatReadState(10, 0, 10));
    }

    @Test
    @DisplayName("Con un ChatStore que no implementa ChatHistoryStore, falla claro (no NPE)")
    void storeSinHistorial() {
        com.modelcollab.assistant.core.ChatStore soloPuerto = new SoloPuerto(store);
        ChatService sinHistorial = new ChatService(soloPuerto, new Publicador(), participantes, List.of());

        assertThatThrownBy(() -> sinHistorial.readState(PROYECTO, p(ana)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ChatHistoryStore");
    }

    /** Delega el puerto y nada más. */
    private record SoloPuerto(InMemoryChatStore d) implements com.modelcollab.assistant.core.ChatStore {
        @Override public ChatMessage save(ChatMessage m) { return d.save(m); }
        @Override public Optional<ChatMessage> findByClientMessageId(String p, String c) { return d.findByClientMessageId(p, c); }
        @Override public Optional<ChatMessage> findById(String p, String id) { return d.findById(p, id); }
        @Override public long nextSeq(String p) { return d.nextSeq(p); }
        @Override public List<ChatMessage> latest(String p, int l) { return d.latest(p, l); }
        @Override public List<ChatMessage> before(String p, long s, int l) { return d.before(p, s, l); }
        @Override public List<ChatMessage> after(String p, long s, int l) { return d.after(p, s, l); }
    }
}
