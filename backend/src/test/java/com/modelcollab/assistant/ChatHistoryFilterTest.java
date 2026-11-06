package com.modelcollab.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.assistant.chat.ChatHistoryPage;
import com.modelcollab.assistant.chat.ChatHistoryQuery;
import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.assistant.store.InMemoryChatStore;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.project.model.ProjectRole;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CU23, tanda 2: búsqueda y filtros del historial, con InMemoryChatStore. Sin base de datos. */
class ChatHistoryFilterTest {

    private static final UUID PROYECTO = UUID.randomUUID();
    private static final UUID OTRO_PROYECTO = UUID.randomUUID();

    private static final class Participantes implements ChatParticipants {
        final Map<List<UUID>, ProjectRole> roles = new ConcurrentHashMap<>();
        UUID miembro(UUID proyecto, ProjectRole rol) {
            UUID id = UUID.randomUUID();
            roles.put(List.of(proyecto, id), rol);
            return id;
        }
        @Override public Optional<ProjectRole> roleIn(UUID p, UUID u) { return Optional.ofNullable(roles.get(List.of(p, u))); }
        @Override public Optional<String> fullName(UUID u) { return Optional.of("Persona"); }
        @Override public boolean diagramBelongsToProject(UUID d, UUID p) { return false; }
    }

    private static final class Publicador implements ChatPublisher {
        @Override public void publishMessage(ChatMessage m) {}
        @Override public void publishTyping(String p, String u, String n, boolean a) {}
    }

    private InMemoryChatStore store;
    private Participantes participantes;
    private ChatService chat;
    private Principal ana;
    private Principal vera;
    private UUID anaId;

    @BeforeEach
    void preparar() {
        store = new InMemoryChatStore();
        participantes = new Participantes();
        chat = new ChatService(store, new Publicador(), participantes, List.of());
        anaId = participantes.miembro(PROYECTO, ProjectRole.EDITOR);
        ana = new StompPrincipal(anaId);
        vera = new StompPrincipal(participantes.miembro(PROYECTO, ProjectRole.VIEWER));
    }

    /** Guarda directo en el store, para controlar tipo de autor y clase. Devuelve el seq. */
    private long guardar(UUID proyecto, ChatAuthorType tipo, ChatMessageKind clase, String texto) {
        long seq = store.nextSeq(proyecto.toString());
        store.save(new ChatMessage(UUID.randomUUID().toString(), proyecto.toString(), null, seq, tipo,
                tipo == ChatAuthorType.USER ? anaId.toString() : null, tipo == ChatAuthorType.USER ? "Ana" : "IA",
                null, null, null, null, null, clase, texto, Instant.EPOCH, null));
        return seq;
    }

    private long usuario(String texto) {
        return guardar(PROYECTO, ChatAuthorType.USER, ChatMessageKind.CHAT, texto);
    }

    private long ia(String texto) {
        return guardar(PROYECTO, ChatAuthorType.AI, ChatMessageKind.ANSWER, texto);
    }

    private long resumen(String texto) {
        return guardar(PROYECTO, ChatAuthorType.AI, ChatMessageKind.SUMMARY, texto);
    }

    private ChatHistoryPage buscar(Long antes, Long despues, Integer limit, String q, String tipo, String clase) {
        return chat.history(PROYECTO, ana, antes, despues, limit, q, tipo, clase);
    }

    private static List<Long> seqs(ChatHistoryPage pagina) {
        return pagina.messages().stream().map(ChatMessage::seq).toList();
    }

    private static ChatErrorCode codigo(Runnable accion) {
        try {
            accion.run();
        } catch (ChatRejectedException e) {
            return e.code();
        }
        throw new AssertionError("se esperaba ChatRejectedException");
    }

    // ------------------------------------------------------------------ q

    @Test
    @DisplayName("q no distingue mayúsculas, encuentra prefijos y se recorta")
    void qSinMayusculas() {
        long uno = usuario("Agreguen los ATRIBUTOS a Cita");
        usuario("nada que ver");
        long tres = ia("Los atributos van con tipo");

        assertThat(seqs(buscar(null, null, null, "atrib", null, null))).containsExactly(uno, tres);
        assertThat(seqs(buscar(null, null, null, "  ATRIB  ", null, null))).containsExactly(uno, tres);
    }

    @Test
    @DisplayName("% y _ del usuario son texto literal, no comodines")
    void qEscapaComodines() {
        long porciento = usuario("vamos al 100% listo");
        usuario("vamos al 100 listo");
        long guion = usuario("la columna fecha_alta");
        usuario("la columna fechaXalta");

        assertThat(seqs(buscar(null, null, null, "0%", null, null))).containsExactly(porciento);
        assertThat(seqs(buscar(null, null, null, "%%", null, null))).isEmpty();
        assertThat(seqs(buscar(null, null, null, "fecha_alta", null, null))).containsExactly(guion);
    }

    @Test
    @DisplayName("La barra invertida y el ! también son literales")
    void qBarraYExclamacion() {
        long ruta = usuario("guardalo en C:\\temp\\x");
        usuario("guardalo en C:temp");
        long hola = usuario("hola!!");

        assertThat(seqs(buscar(null, null, null, "C:\\temp", null, null))).containsExactly(ruta);
        assertThat(seqs(buscar(null, null, null, "a!!", null, null))).containsExactly(hola);
    }

    @Test
    @DisplayName("q solo busca dentro del proyecto")
    void qSoloEnElProyecto() {
        guardar(OTRO_PROYECTO, ChatAuthorType.USER, ChatMessageKind.CHAT, "atributos de otro proyecto");
        long propio = usuario("atributos de este");

        assertThat(seqs(buscar(null, null, null, "atributos", null, null))).containsExactly(propio);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "   ", "a", "  a  ", "😀"})
    @DisplayName("q de menos de 2 caracteres (tras recortar): INVALID_PAYLOAD")
    void qCorta(String q) {
        assertThat(codigo(() -> buscar(null, null, null, q, null, null))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("q: 2 y 100 caracteres valen; 101 no")
    void qLimites() {
        usuario("ab" + "x".repeat(98));

        assertThat(buscar(null, null, null, "ab", null, null).messages()).hasSize(1);
        assertThat(buscar(null, null, null, "ab" + "x".repeat(98), null, null).messages()).hasSize(1);
        assertThat(codigo(() -> buscar(null, null, null, "x".repeat(101), null, null)))
                .isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        // Se cuenta después de recortar: 100 con espacios alrededor vale.
        assertThat(buscar(null, null, null, "  " + "ab" + "x".repeat(98) + "  ", null, null).messages()).hasSize(1);
    }

    // ---------------------------------------------------------- authorType y kind

    @Test
    @DisplayName("authorType=AI ('Solo IA') y kind=SUMMARY ('Resúmenes'), cada uno por separado")
    void filtrosSeparados() {
        usuario("hola");
        long r1 = ia("respuesta");
        long r2 = resumen("resumen del día");
        guardar(PROYECTO, ChatAuthorType.SYSTEM, ChatMessageKind.SYSTEM, "aviso");

        assertThat(seqs(buscar(null, null, null, null, "AI", null))).containsExactly(r1, r2);
        assertThat(seqs(buscar(null, null, null, null, null, "SUMMARY"))).containsExactly(r2);
        assertThat(seqs(buscar(null, null, null, null, "system", null))).containsExactly(4L);
        assertThat(seqs(buscar(null, null, null, null, " user ", null))).containsExactly(1L);
    }

    @Test
    @DisplayName("Filtros combinados: authorType + kind + q")
    void filtrosCombinados() {
        usuario("resumen pedido por Ana");
        ia("no es un resumen, es una respuesta");
        long r = resumen("Resumen: agregaron Cita");
        resumen("Otro cierre del día");

        assertThat(seqs(buscar(null, null, null, "resumen", "AI", "SUMMARY"))).containsExactly(r);
        assertThat(seqs(buscar(null, null, null, "resumen", "AI", null))).containsExactly(2L, r);
        assertThat(seqs(buscar(null, null, null, "resumen", "USER", "SUMMARY"))).isEmpty();
    }

    @ParameterizedTest(name = "authorType={0}")
    @ValueSource(strings = {"ROBOT", "", "  ", "A I"})
    @DisplayName("authorType desconocido o en blanco: INVALID_PAYLOAD")
    void authorTypeInvalido(String valor) {
        assertThat(codigo(() -> buscar(null, null, null, null, valor, null))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    @ParameterizedTest(name = "kind={0}")
    @ValueSource(strings = {"QUESTION", "", "summaries"})
    @DisplayName("kind desconocido o en blanco: INVALID_PAYLOAD")
    void kindInvalido(String valor) {
        assertThat(codigo(() -> buscar(null, null, null, null, null, valor))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    // ------------------------------------------------------ cursores con filtros

    /** 1..12: los seq pares son de la IA, los impares de Ana. */
    private void alternados() {
        for (int i = 1; i <= 12; i++) {
            if (i % 2 == 0) {
                ia("ia " + i);
            } else {
                usuario("ana " + i);
            }
        }
    }

    @Test
    @DisplayName("Sin cursor: los más nuevos del conjunto filtrado; hasMore = hay más viejos filtrados")
    void sinCursorFiltrado() {
        alternados();

        ChatHistoryPage pagina = buscar(null, null, 3, null, "AI", null);
        assertThat(seqs(pagina)).containsExactly(8L, 10L, 12L);
        assertThat(pagina.hasMore()).isTrue();

        ChatHistoryPage todo = buscar(null, null, 6, null, "AI", null);
        assertThat(seqs(todo)).containsExactly(2L, 4L, 6L, 8L, 10L, 12L);
        assertThat(todo.hasMore()).as("hay 12 mensajes, pero solo 6 de la IA").isFalse();
    }

    @Test
    @DisplayName("beforeSeq con filtro: los anteriores filtrados; hasMore sobre el conjunto filtrado")
    void antesFiltrado() {
        alternados();

        ChatHistoryPage pagina = buscar(8L, null, 2, null, "AI", null);
        assertThat(seqs(pagina)).containsExactly(4L, 6L);
        assertThat(pagina.hasMore()).isTrue();

        ChatHistoryPage ultima = buscar(4L, null, 2, null, "AI", null);
        assertThat(seqs(ultima)).containsExactly(2L);
        assertThat(ultima.hasMore()).as("el 1 y el 3 son de Ana: no cuentan").isFalse();
    }

    @Test
    @DisplayName("afterSeq con filtro: los siguientes filtrados; hasMore = hay más nuevos filtrados")
    void despuesFiltrado() {
        alternados();

        ChatHistoryPage pagina = buscar(null, 5L, 2, null, "AI", null);
        assertThat(seqs(pagina)).containsExactly(6L, 8L);
        assertThat(pagina.hasMore()).isTrue();

        ChatHistoryPage ultima = buscar(null, 9L, 2, null, "AI", null);
        assertThat(seqs(ultima)).containsExactly(10L, 12L);
        assertThat(ultima.hasMore()).isFalse();

        ChatHistoryPage conQ = buscar(null, 0L, 50, "ana 1", null, null);
        assertThat(seqs(conQ)).containsExactly(1L, 11L);
    }

    @Test
    @DisplayName("limit con filtros: 50 por defecto y acotado a 1..100")
    void limitConFiltros() {
        for (int i = 0; i < 120; i++) {
            ia("respuesta " + i);
        }

        assertThat(buscar(null, null, null, null, "AI", null).messages()).hasSize(50);
        assertThat(buscar(null, null, 500, null, "AI", null).messages()).hasSize(100);
        assertThat(buscar(null, null, 0, null, "AI", null).messages()).hasSize(1);
    }

    @Test
    @DisplayName("Los dos cursores, o uno negativo, siguen siendo INVALID_PAYLOAD con filtros")
    void cursoresInvalidos() {
        assertThat(codigo(() -> buscar(5L, 1L, null, "hola", null, null))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigo(() -> buscar(-1L, null, null, null, "AI", null))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("Sin filtros da lo mismo que la firma de siempre")
    void sinFiltrosIgualQueAntes() {
        alternados();

        assertThat(chat.history(PROYECTO, ana, 9L, null, 3, null, null, null))
                .isEqualTo(chat.history(PROYECTO, ana, 9L, null, 3));
        assertThat(chat.history(PROYECTO, ana, null, 2L, 4, null, null, null))
                .isEqualTo(chat.history(PROYECTO, ana, null, 2L, 4));
    }

    // ------------------------------------------------------------- membresía

    @Test
    @DisplayName("Un VIEWER busca y filtra")
    void viewerPuede() {
        long r = resumen("resumen");
        assertThat(seqs(chat.history(PROYECTO, vera, null, null, null, "resu", "ai", "summary"))).containsExactly(r);
    }

    @Test
    @DisplayName("Quien no es miembro: NOT_PROJECT_MEMBER, antes de validar los filtros")
    void noMiembro() {
        Principal ajeno = new StompPrincipal(participantes.miembro(OTRO_PROYECTO, ProjectRole.EDITOR));

        assertThat(codigo(() -> chat.history(PROYECTO, ajeno, null, null, null, "hola", null, null)))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigo(() -> chat.history(PROYECTO, ajeno, null, null, null, "a", "ROBOT", "X")))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
    }

    // ------------------------------------------------------------ el record

    @Test
    @DisplayName("ChatHistoryQuery rechaza los dos cursores, limit no positivo y proyecto nulo")
    void consultaInvalida() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new ChatHistoryQuery(PROYECTO, 1L, 2L, 5, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new ChatHistoryQuery(PROYECTO, null, null, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new ChatHistoryQuery(null, null, null, 5, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
