package com.modelcollab.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.assistant.chat.ChatHistoryPage;
import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.assistant.chat.ChatPostRequest;
import com.modelcollab.assistant.chat.ChatPostResult;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.assistant.core.UserMessageListener;
import com.modelcollab.assistant.store.InMemoryChatStore;
import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.project.model.ProjectRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** CU19, etapa 1: reglas de escritura del chat con InMemoryChatStore, sin base de datos. */
class ChatServiceTest {

    private static final UUID PROYECTO = UUID.randomUUID();

    /** Reloj que sólo avanza cuando el test se lo pide. */
    private static final class RelojDePrueba extends Clock {
        private long ahora = 1_000_000L;

        void avanzar(long ms) {
            ahora += ms;
        }

        @Override public long millis() { return ahora; }
        @Override public Instant instant() { return Instant.ofEpochMilli(ahora); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
    }

    /** Miembros y nombres en un mapa, en lugar de PostgreSQL. */
    private static final class ParticipantesDePrueba implements ChatParticipants {
        final Map<UUID, ProjectRole> roles = new HashMap<>();
        final Map<UUID, String> nombres = new HashMap<>();
        /** diagramId -> proyecto al que pertenece. */
        final Map<UUID, UUID> diagramas = new HashMap<>();

        UUID miembro(String nombre, ProjectRole rol) {
            UUID id = UUID.randomUUID();
            roles.put(id, rol);
            nombres.put(id, nombre);
            return id;
        }

        @Override
        public Optional<ProjectRole> roleIn(UUID projectId, UUID userId) {
            return PROYECTO.equals(projectId) ? Optional.ofNullable(roles.get(userId)) : Optional.empty();
        }

        @Override
        public Optional<String> fullName(UUID userId) {
            return Optional.ofNullable(nombres.get(userId));
        }

        @Override
        public boolean diagramBelongsToProject(UUID diagramId, UUID projectId) {
            return projectId.equals(diagramas.get(diagramId));
        }
    }

    /**
     * Publicador que anota lo que difunde y, en el momento de difundir, si el mensaje ya estaba
     * guardado. Así se comprueba "primero se persiste, después se difunde".
     */
    private final class PublicadorDePrueba implements ChatPublisher {
        final List<ChatMessage> difundidos = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> yaGuardado = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publishMessage(ChatMessage message) {
            yaGuardado.add(store.findById(message.projectId(), message.id()).isPresent());
            difundidos.add(message);
        }

        @Override
        public void publishTyping(String projectId, String forUserId, String forUserName, boolean active) {}
    }

    private InMemoryChatStore store;
    private PublicadorDePrueba publicador;
    private ParticipantesDePrueba participantes;
    private List<ChatMessage> avisados;
    private RelojDePrueba reloj;
    private ChatService chat;
    private UUID ana;

    @BeforeEach
    void preparar() {
        store = new InMemoryChatStore();
        publicador = new PublicadorDePrueba();
        participantes = new ParticipantesDePrueba();
        avisados = Collections.synchronizedList(new ArrayList<>());
        reloj = new RelojDePrueba();
        UserMessageListener orquestador = avisados::add;
        chat = new ChatService(store, publicador, participantes, List.of(orquestador), reloj);
        ana = participantes.miembro("Ana Pérez", ProjectRole.EDITOR);
    }

    private static ChatPostRequest pedido(String clientMessageId, String texto) {
        return new ChatPostRequest(clientMessageId, texto, null, null);
    }

    private ChatPostResult escribir(UUID quien, String clientMessageId, String texto) {
        return chat.postFromUser(PROYECTO, new StompPrincipal(quien), pedido(clientMessageId, texto));
    }

    private static ChatErrorCode codigoDe(Runnable accion) {
        try {
            accion.run();
        } catch (ChatRejectedException e) {
            return e.code();
        }
        throw new AssertionError("Se esperaba un rechazo");
    }

    // ------------------------------------------------------------ camino feliz

    @Test
    @DisplayName("El autor, el nombre y el color salen del Principal, con el color de los cursores")
    void autorDelPrincipal() {
        ChatMessage m = escribir(ana, "c-1", "  hola equipo  ").message();

        assertThat(m.authorType()).isEqualTo(ChatAuthorType.USER);
        assertThat(m.authorId()).isEqualTo(ana.toString());
        assertThat(m.authorName()).isEqualTo("Ana Pérez");
        assertThat(m.authorColor()).isEqualTo(Participante.de(ana, "Ana Pérez").color());
        assertThat(m.text()).isEqualTo("hola equipo");
        assertThat(m.seq()).isEqualTo(1);
        assertThat(m.createdAt()).isEqualTo(reloj.instant());
    }

    @Test
    @DisplayName("Se guarda antes de difundir, se difunde una vez y después se avisa al orquestador")
    void persisteDifundeAvisa() {
        ChatMessage m = escribir(ana, "c-1", "hola").message();

        assertThat(publicador.difundidos).containsExactly(m);
        assertThat(publicador.yaGuardado).containsExactly(true);
        assertThat(avisados).containsExactly(m);
    }

    @Test
    @DisplayName("Un Participante de sesión ajeno al Principal se ignora: manda el Principal")
    void participanteDeSesionAjenoSeIgnora() {
        Participante impostor = Participante.de(UUID.randomUUID(), "Impostor");

        ChatMessage m =
                chat.postFromUser(PROYECTO, new StompPrincipal(ana), pedido("c-1", "hola"), impostor)
                        .message();

        assertThat(m.authorId()).isEqualTo(ana.toString());
        assertThat(m.authorName()).isEqualTo("Ana Pérez");
    }

    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    @DisplayName("Cualquier rol, VIEWER incluido, puede escribir en el chat")
    void cualquierRolEscribe(ProjectRole rol) {
        UUID quien = participantes.miembro("Persona", rol);

        assertThat(escribir(quien, "c-1", "hola").duplicate()).isFalse();
    }

    @Test
    @DisplayName("seq creciente y sin huecos con mensajes concurrentes, y se difunde en orden de seq")
    void seqConcurrenteSinHuecos() throws Exception {
        int personas = 10;
        int porPersona = ChatService.MENSAJES_POR_MINUTO;
        List<UUID> autores = new ArrayList<>();
        for (int i = 0; i < personas; i++) {
            autores.add(participantes.miembro("P" + i, ProjectRole.EDITOR));
        }
        ExecutorService hilos = Executors.newFixedThreadPool(personas);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        for (UUID autor : autores) {
            tareas.add(hilos.submit(() -> {
                largada.await();
                for (int j = 0; j < porPersona; j++) {
                    escribir(autor, autor + "-" + j, "mensaje " + j);
                }
                return null;
            }));
        }
        largada.countDown();
        for (Future<?> t : tareas) {
            t.get();
        }
        hilos.shutdown();

        List<Long> esperado = LongStream.rangeClosed(1, (long) personas * porPersona).boxed().toList();
        assertThat(store.latest(PROYECTO.toString(), 1000)).extracting(ChatMessage::seq)
                .containsExactlyElementsOf(esperado);
        assertThat(publicador.difundidos).extracting(ChatMessage::seq)
                .containsExactlyElementsOf(esperado);
    }

    // ----------------------------------------------------------- idempotencia

    @Test
    @DisplayName("Un duplicado devuelve el original, no se vuelve a difundir ni a avisar")
    void idempotencia() {
        ChatPostResult primero = escribir(ana, "c-1", "hola");
        ChatPostResult reintento = escribir(ana, "c-1", "hola");

        assertThat(primero.duplicate()).isFalse();
        assertThat(reintento.duplicate()).isTrue();
        assertThat(reintento.message()).isEqualTo(primero.message());
        assertThat(publicador.difundidos).hasSize(1);
        assertThat(avisados).hasSize(1);
        assertThat(store.nextSeq(PROYECTO.toString())).as("no se consumió otro seq").isEqualTo(2);
    }

    @Test
    @DisplayName("Reintentos simultáneos del mismo mensaje producen un solo mensaje y una sola difusión")
    void idempotenciaConcurrente() throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ChatPostResult>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tareas.add(hilos.submit(() -> {
                largada.await();
                return escribir(ana, "c-1", "hola");
            }));
        }
        largada.countDown();
        int nuevos = 0;
        for (Future<ChatPostResult> t : tareas) {
            nuevos += t.get().duplicate() ? 0 : 1;
        }
        hilos.shutdown();

        assertThat(nuevos).isEqualTo(1);
        assertThat(publicador.difundidos).hasSize(1);
        assertThat(store.latest(PROYECTO.toString(), 10)).hasSize(1);
    }

    @Test
    @DisplayName("Un duplicado no consume cupo del límite por minuto")
    void duplicadoNoConsumeCupo() {
        escribir(ana, "c-0", "hola");
        for (int i = 0; i < 50; i++) {
            escribir(ana, "c-0", "hola");
        }

        for (int i = 1; i < ChatService.MENSAJES_POR_MINUTO; i++) {
            escribir(ana, "c-" + i, "hola");
        }
    }

    @Test
    @DisplayName("Reusar el clientMessageId de otra persona es INVALID_PAYLOAD, no un duplicado")
    void clientIdAjeno() {
        UUID beto = participantes.miembro("Beto", ProjectRole.EDITOR);
        escribir(ana, "c-1", "hola");

        assertThat(codigoDe(() -> escribir(beto, "c-1", "otra cosa")))
                .isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    // -------------------------------------------------------------- rechazos

    @Test
    @DisplayName("Un no miembro es rechazado con NOT_PROJECT_MEMBER y no se guarda ni se difunde nada")
    void noMiembro() {
        UUID extrano = UUID.randomUUID();

        assertThat(codigoDe(() -> escribir(extrano, "c-1", "hola")))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigoDe(() -> chat.postFromUser(PROYECTO, null, pedido("c-1", "hola"))))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(store.latest(PROYECTO.toString(), 10)).isEmpty();
        assertThat(publicador.difundidos).isEmpty();
        assertThat(avisados).isEmpty();
    }

    @Test
    @DisplayName("Miembro de otro proyecto no puede escribir en este")
    void miembroDeOtroProyecto() {
        assertThat(codigoDe(() -> chat.postFromUser(
                        UUID.randomUUID(), new StompPrincipal(ana), pedido("c-1", "hola"))))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
    }

    @Test
    @DisplayName("Texto vacío, en blanco, nulo o de más de 2000 caracteres: INVALID_PAYLOAD")
    void textoInvalido() {
        assertThat(codigoDe(() -> escribir(ana, "c-1", ""))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> escribir(ana, "c-1", "  \n "))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> escribir(ana, "c-1", null))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> escribir(ana, "c-1", "a".repeat(ChatService.LARGO_MAXIMO + 1))))
                .isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> chat.postFromUser(PROYECTO, new StompPrincipal(ana), null)))
                .isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(publicador.difundidos).isEmpty();
    }

    @Test
    @DisplayName("2000 caracteres exactos pasan, y los emojis cuentan como un carácter")
    void textoEnElLimite() {
        escribir(ana, "c-1", "a".repeat(ChatService.LARGO_MAXIMO));
        escribir(ana, "c-2", "😀".repeat(ChatService.LARGO_MAXIMO));

        assertThat(publicador.difundidos).hasSize(2);
    }

    @Test
    @DisplayName("clientMessageId faltante o en blanco: INVALID_PAYLOAD")
    void clientIdFaltante() {
        assertThat(codigoDe(() -> escribir(ana, null, "hola"))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> escribir(ana, " ", "hola"))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("replyToId de un mensaje que no existe en el proyecto: INVALID_PAYLOAD")
    void replyToInexistente() {
        ChatMessage original = escribir(ana, "c-1", "hola").message();

        ChatMessage respuesta = chat.postFromUser(PROYECTO, new StompPrincipal(ana),
                new ChatPostRequest("c-2", "sigo", null, original.id()), null).message();
        assertThat(respuesta.replyToId()).isEqualTo(original.id());
        assertThat(codigoDe(() -> chat.postFromUser(PROYECTO, new StompPrincipal(ana),
                        new ChatPostRequest("c-3", "sigo", null, "no-existe"))))
                .isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("diagramId del proyecto se acepta y queda guardado en el mensaje")
    void diagramaValido() {
        UUID diagrama = UUID.randomUUID();
        participantes.diagramas.put(diagrama, PROYECTO);

        ChatMessage m = chat.postFromUser(PROYECTO, new StompPrincipal(ana),
                new ChatPostRequest("c-1", "hola", diagrama, null)).message();

        assertThat(m.diagramId()).isEqualTo(diagrama.toString());
        assertThat(publicador.difundidos).hasSize(1);
    }

    @Test
    @DisplayName("diagramId inexistente o de otro proyecto: el mismo INVALID_PAYLOAD, sin guardar")
    void diagramaInvalido() {
        UUID ajeno = UUID.randomUUID();
        participantes.diagramas.put(ajeno, UUID.randomUUID());

        for (UUID diagrama : List.of(UUID.randomUUID(), ajeno)) {
            ChatRejectedException e = null;
            try {
                chat.postFromUser(PROYECTO, new StompPrincipal(ana),
                        new ChatPostRequest("c-" + diagrama, "hola", diagrama, null));
            } catch (ChatRejectedException ex) {
                e = ex;
            }
            assertThat(e).isNotNull();
            assertThat(e.code()).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
            assertThat(e.getMessage()).isEqualTo("El diagrama no es válido para este proyecto");
        }
        assertThat(store.latest(PROYECTO.toString(), 10)).isEmpty();
        assertThat(publicador.difundidos).isEmpty();
        assertThat(avisados).isEmpty();
    }

    @Test
    @DisplayName("Un diagramId inválido no consume cupo del límite por minuto")
    void diagramaInvalidoNoConsumeCupo() {
        for (int i = 0; i < ChatService.MENSAJES_POR_MINUTO + 5; i++) {
            int n = i;
            codigoDe(() -> chat.postFromUser(PROYECTO, new StompPrincipal(ana),
                    new ChatPostRequest("x-" + n, "hola", UUID.randomUUID(), null)));
        }
        escribir(ana, "c-ok", "hola");
        assertThat(publicador.difundidos).hasSize(1);
    }

    @Test
    @DisplayName("El mensaje 21 en un minuto es RATE_LIMITED; pasado el minuto vuelve a poder")
    void limitePorMinuto() {
        for (int i = 0; i < ChatService.MENSAJES_POR_MINUTO; i++) {
            escribir(ana, "c-" + i, "hola");
        }

        assertThat(codigoDe(() -> escribir(ana, "c-extra", "hola"))).isEqualTo(ChatErrorCode.RATE_LIMITED);
        UUID beto = participantes.miembro("Beto", ProjectRole.EDITOR);
        assertThat(escribir(beto, "b-1", "yo sí puedo").duplicate()).as("el límite es por usuario").isFalse();

        reloj.avanzar(60_001);
        assertThat(escribir(ana, "c-extra", "hola").duplicate()).isFalse();
    }

    @Test
    @DisplayName("Si falla el orquestador o la difusión, el mensaje de la persona queda aceptado")
    void fallasAjenasNoRechazan() {
        ChatPublisher roto = new ChatPublisher() {
            @Override public void publishMessage(ChatMessage message) { throw new IllegalStateException("broker caído"); }
            @Override public void publishTyping(String p, String u, String n, boolean a) {}
        };
        UserMessageListener explota = m -> { throw new IllegalStateException("IA caída"); };
        ChatService fragil = new ChatService(store, roto, participantes, List.of(explota), reloj);

        ChatPostResult r = fragil.postFromUser(PROYECTO, new StompPrincipal(ana), pedido("c-1", "hola"));

        assertThat(r.duplicate()).isFalse();
        assertThat(store.findByClientMessageId(PROYECTO.toString(), "c-1")).isPresent();
    }

    @Test
    @DisplayName("ChatRejectedException lleva el status de cada código")
    void statusDeLosCodigos() {
        assertThat(ChatErrorCode.NOT_PROJECT_MEMBER.status()).isEqualTo(403);
        assertThat(ChatErrorCode.INVALID_PAYLOAD.status()).isEqualTo(400);
        assertThat(ChatErrorCode.RATE_LIMITED.status()).isEqualTo(429);
        assertThatThrownBy(() -> escribir(UUID.randomUUID(), "c", "x"))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessageContaining("miembro");
    }

    // ============================================================== historial

    /** n mensajes directo al store (seq 1..n), sin pasar por el límite por minuto. */
    private void sembrar(int n) {
        String proyecto = PROYECTO.toString();
        for (int i = 0; i < n; i++) {
            long seq = store.nextSeq(proyecto);
            store.save(new ChatMessage(UUID.randomUUID().toString(), proyecto, null, seq,
                    ChatAuthorType.USER, ana.toString(), "Ana Pérez", "#000000", null, null, null,
                    "seed-" + seq, ChatMessageKind.CHAT, "m" + seq, reloj.instant(), null));
        }
    }

    private ChatHistoryPage leer(UUID quien, Long beforeSeq, Long afterSeq, Integer limit) {
        return chat.history(PROYECTO, new StompPrincipal(quien), beforeSeq, afterSeq, limit);
    }

    private static List<Long> seqs(ChatHistoryPage pagina) {
        return pagina.messages().stream().map(ChatMessage::seq).toList();
    }

    @Test
    @DisplayName("Historial sin cursores: los últimos limit, ascendente, hasMore si hay más viejos")
    void historialUltimos() {
        sembrar(5);

        ChatHistoryPage dos = leer(ana, null, null, 2);
        assertThat(seqs(dos)).containsExactly(4L, 5L);
        assertThat(dos.hasMore()).isTrue();

        ChatHistoryPage todos = leer(ana, null, null, 5);
        assertThat(seqs(todos)).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(todos.hasMore()).isFalse();
    }

    @Test
    @DisplayName("afterSeq con 5 mensajes nuevos y limit 2: los seq MÁS BAJOS posteriores y hasMore")
    void historialAfter() {
        sembrar(3);
        sembrar(5); // los nuevos: 4..8

        ChatHistoryPage pagina = leer(ana, null, 3L, 2);
        assertThat(seqs(pagina)).containsExactly(4L, 5L);
        assertThat(pagina.hasMore()).isTrue();

        ChatHistoryPage ultima = leer(ana, null, 6L, 2);
        assertThat(seqs(ultima)).containsExactly(7L, 8L);
        assertThat(ultima.hasMore()).isFalse();

        assertThat(leer(ana, null, 8L, 2).messages()).isEmpty();
    }

    @Test
    @DisplayName("beforeSeq: los inmediatamente anteriores, ascendente, hasMore si hay más viejos")
    void historialBefore() {
        sembrar(8);

        ChatHistoryPage pagina = leer(ana, 6L, null, 2);
        assertThat(seqs(pagina)).containsExactly(4L, 5L);
        assertThat(pagina.hasMore()).isTrue();

        ChatHistoryPage primera = leer(ana, 3L, null, 5);
        assertThat(seqs(primera)).containsExactly(1L, 2L);
        assertThat(primera.hasMore()).isFalse();
    }

    @Test
    @DisplayName("limit: 50 por defecto y acotado a 1..100")
    void historialLimite() {
        sembrar(120);

        assertThat(leer(ana, null, null, null).messages()).hasSize(ChatService.HISTORIAL_POR_DEFECTO);
        assertThat(leer(ana, null, null, 500).messages()).hasSize(ChatService.HISTORIAL_MAXIMO);
        assertThat(seqs(leer(ana, null, null, 0))).containsExactly(120L);
        assertThat(seqs(leer(ana, null, null, -5))).containsExactly(120L);
        assertThat(leer(ana, null, null, 500).hasMore()).isTrue();
    }

    @Test
    @DisplayName("beforeSeq y afterSeq a la vez, o negativos: INVALID_PAYLOAD")
    void historialCursoresInvalidos() {
        assertThat(codigoDe(() -> leer(ana, 5L, 2L, 10))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> leer(ana, -1L, null, 10))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> leer(ana, null, -1L, 10))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
    }

    @Test
    @DisplayName("Historial: un VIEWER lee; un no miembro, sin Principal o de otro proyecto, no")
    void historialMembresia() {
        sembrar(2);
        UUID visor = participantes.miembro("Visor", ProjectRole.VIEWER);

        assertThat(seqs(leer(visor, null, null, 10))).containsExactly(1L, 2L);
        assertThat(codigoDe(() -> leer(UUID.randomUUID(), null, null, 10)))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigoDe(() -> chat.history(PROYECTO, null, null, null, 10)))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
        assertThat(codigoDe(() -> chat.history(
                        UUID.randomUUID(), new StompPrincipal(ana), null, null, 10)))
                .isEqualTo(ChatErrorCode.NOT_PROJECT_MEMBER);
    }

    @Test
    @DisplayName("Historial de un proyecto vacío: lista vacía y sin más")
    void historialVacio() {
        ChatHistoryPage pagina = leer(ana, null, null, null);
        assertThat(pagina.messages()).isEmpty();
        assertThat(pagina.hasMore()).isFalse();
    }

    // ================================================= escritura de la IA y el sistema

    @Test
    @DisplayName("postFromAi (AI): siguiente seq, difundido, dirigido y en respuesta a la pregunta, sin listeners")
    void respuestaDeLaIa() {
        ChatMessage pregunta = escribir(ana, "c-1", "@IA ¿qué es una composición?").message();

        ChatMessage r = chat.postFromAi(PROYECTO, ChatAuthorType.AI, "Ana, es una relación fuerte.", pregunta);

        assertThat(r.seq()).isEqualTo(pregunta.seq() + 1);
        assertThat(r.authorType()).isEqualTo(ChatAuthorType.AI);
        assertThat(r.authorId()).isNull();
        assertThat(r.authorName()).isEqualTo("IA");
        assertThat(r.authorColor()).isNull();
        assertThat(r.addressedToId()).isEqualTo(ana.toString());
        assertThat(r.addressedToName()).isEqualTo("Ana Pérez");
        assertThat(r.replyToId()).isEqualTo(pregunta.id());
        assertThat(r.kind()).isEqualTo(ChatMessageKind.ANSWER);
        assertThat(r.text()).isEqualTo("Ana, es una relación fuerte.");
        assertThat(store.findById(PROYECTO.toString(), r.id())).contains(r);
        assertThat(publicador.difundidos).extracting(ChatMessage::id).containsExactly(pregunta.id(), r.id());
        assertThat(publicador.yaGuardado).containsOnly(true);
        assertThat(avisados).as("la IA no dispara listeners").containsExactly(pregunta);
    }

    @Test
    @DisplayName("postFromAi (SYSTEM): sin destinatario ni respuesta, kind SYSTEM, sin listeners")
    void mensajeDelSistema() {
        ChatMessage pregunta = escribir(ana, "c-1", "@IA hola").message();

        ChatMessage s1 = chat.postFromAi(PROYECTO, ChatAuthorType.SYSTEM, "La IA no está disponible ahora", pregunta);
        ChatMessage s2 = chat.postFromAi(PROYECTO, ChatAuthorType.SYSTEM, "La IA no está disponible ahora", null);

        assertThat(s1.authorType()).isEqualTo(ChatAuthorType.SYSTEM);
        assertThat(s1.authorName()).isEqualTo("Sistema");
        assertThat(s1.addressedToId()).isNull();
        assertThat(s1.addressedToName()).isNull();
        assertThat(s1.replyToId()).isNull();
        assertThat(s1.kind()).isEqualTo(ChatMessageKind.SYSTEM);
        assertThat(List.of(s1.seq(), s2.seq())).containsExactly(2L, 3L);
        assertThat(publicador.difundidos).hasSize(3);
        assertThat(avisados).containsExactly(pregunta);
    }

    @Test
    @DisplayName("Una pregunta recibe una sola respuesta de la IA: el reintento no se guarda ni se difunde")
    void respuestaIdempotente() {
        ChatMessage pregunta = escribir(ana, "c-1", "@IA hola").message();

        ChatMessage primera = chat.postFromAi(PROYECTO, ChatAuthorType.AI, "Ana, hola", pregunta);
        ChatMessage otra = chat.postFromAi(PROYECTO, ChatAuthorType.AI, "Ana, hola de nuevo", pregunta);

        assertThat(otra).isEqualTo(primera);
        assertThat(publicador.difundidos).hasSize(2);
        assertThat(chat.postFromAi(PROYECTO, ChatAuthorType.SYSTEM, "aviso", null).seq())
                .as("el reintento no consumió seq").isEqualTo(3L);
    }

    @Test
    @DisplayName("Personas e IA escribiendo a la vez: seq sin huecos ni repetidos y difusión en orden")
    void iaYPersonasConcurrentes() throws Exception {
        List<ChatMessage> preguntas = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID quien = participantes.miembro("Persona " + i, ProjectRole.EDITOR);
            preguntas.add(escribir(quien, "q-" + i, "@IA pregunta " + i).message());
        }
        List<UUID> otros = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            otros.add(participantes.miembro("Otra " + i, ProjectRole.VIEWER));
        }
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<?>> tareas = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ChatMessage p = preguntas.get(i);
                UUID otro = otros.get(i);
                int n = i;
                tareas.add(hilos.submit(() -> {
                    largada.await();
                    return chat.postFromAi(PROYECTO, ChatAuthorType.AI, "respuesta " + n, p);
                }));
                tareas.add(hilos.submit(() -> {
                    largada.await();
                    return escribir(otro, "o-" + n, "comentario " + n);
                }));
            }
            largada.countDown();
            for (Future<?> t : tareas) {
                t.get();
            }
        } finally {
            hilos.shutdownNow();
        }

        List<Long> seqs = store.latest(PROYECTO.toString(), 100).stream().map(ChatMessage::seq).toList();
        assertThat(seqs).containsExactlyElementsOf(LongStream.rangeClosed(1, 30).boxed().toList());
        assertThat(publicador.difundidos).extracting(ChatMessage::seq).isSorted();
        assertThat(avisados).as("sólo los 20 mensajes de personas").hasSize(20);
    }

    @Test
    @DisplayName("postFromAi rechaza USER, texto vacío y AI sin pregunta")
    void postFromAiInvalido() {
        ChatMessage pregunta = escribir(ana, "c-1", "hola").message();

        assertThatThrownBy(() -> chat.postFromAi(PROYECTO, ChatAuthorType.USER, "x", pregunta))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chat.postFromAi(PROYECTO, ChatAuthorType.AI, "  ", pregunta))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chat.postFromAi(PROYECTO, ChatAuthorType.AI, "x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(publicador.difundidos).hasSize(1);
    }

    @Test
    @DisplayName("Una persona no puede usar los prefijos reservados ai- y sys- como clientMessageId")
    void prefijosReservados() {
        assertThat(codigoDe(() -> escribir(ana, "ai-" + UUID.randomUUID(), "hola")))
                .isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(codigoDe(() -> escribir(ana, "sys-1", "hola"))).isEqualTo(ChatErrorCode.INVALID_PAYLOAD);
        assertThat(publicador.difundidos).isEmpty();
    }
}
