package com.modelcollab.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.assistant.chat.ChatPostRequest;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatPublisher;
import com.modelcollab.assistant.store.InMemoryChatStore;
import com.modelcollab.collaboration.interceptor.StompPrincipal;
import com.modelcollab.project.model.ProjectRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CU19, etapa 4, entrega 3b: el orquestador de la IA. InMemoryChatStore, un modelo de prueba
 * sobre FakeChatClient y un ejecutor síncrono (o que retiene tareas); sin base de datos ni red.
 */
class AssistantOrchestratorTest {

    private static final UUID PROYECTO = UUID.randomUUID();
    private static final UUID OTRO = UUID.randomUUID();

    // ------------------------------------------------------------- dobles

    private static final class RelojDePrueba extends Clock {
        private volatile long ahora = 1_000_000L;
        void avanzar(Duration d) { ahora += d.toMillis(); }
        @Override public long millis() { return ahora; }
        @Override public Instant instant() { return Instant.ofEpochMilli(ahora); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
    }

    private record Escribiendo(String projectId, String forUserId, boolean active) {}

    private static final class Publicador implements ChatPublisher {
        final List<ChatMessage> difundidos = Collections.synchronizedList(new ArrayList<>());
        final List<Escribiendo> escribiendo = Collections.synchronizedList(new ArrayList<>());
        @Override public void publishMessage(ChatMessage m) { difundidos.add(m); }
        @Override public void publishTyping(String p, String u, String n, boolean a) {
            escribiendo.add(new Escribiendo(p, u, a));
        }
    }

    private static final class Participantes implements ChatParticipants {
        final Map<UUID, String> nombres = new ConcurrentHashMap<>();
        @Override public Optional<ProjectRole> roleIn(UUID p, UUID u) {
            return nombres.containsKey(u) ? Optional.of(ProjectRole.VIEWER) : Optional.empty();
        }
        @Override public Optional<String> fullName(UUID u) { return Optional.ofNullable(nombres.get(u)); }
        @Override public boolean diagramBelongsToProject(UUID d, UUID p) { return false; }
    }

    /** Por defecto responde como FakeChatClient; cada test puede cambiar el comportamiento. */
    private static final class Modelo implements AiChatModelClient {
        final List<ChatRequest> pedidos = Collections.synchronizedList(new ArrayList<>());
        volatile Function<ChatRequest, ChatCompletion> comportamiento = new FakeChatClient()::chat;
        @Override public ChatCompletion chat(ChatRequest r) { pedidos.add(r); return comportamiento.apply(r); }
        @Override public String descripcion() { return "modelo de prueba"; }
        @Override public boolean esSimulado() { return true; }
    }

    /** Síncrono, salvo cuando {@code retener}: entonces guarda las tareas para correrlas después. */
    private static final class Control {
        volatile boolean retener;
        final List<Runnable> retenidas = new ArrayList<>();
        void execute(Runnable r) {
            if (retener) {
                retenidas.add(r);
            } else {
                r.run();
            }
        }
        void soltar() {
            retener = false;
            List<Runnable> todas = new ArrayList<>(retenidas);
            retenidas.clear();
            todas.forEach(Runnable::run);
        }
    }

    // ------------------------------------------------------------- armado

    private RelojDePrueba reloj;
    private InMemoryChatStore store;
    private Publicador publicador;
    private Participantes participantes;
    private Modelo modelo;
    private Control control;
    private volatile AssistantMode modo;
    private ChatService chat;
    private AssistantOrchestrator orquestador;
    private UUID ana;
    private UUID carlos;

    @BeforeEach
    void preparar() {
        control = new Control();
        armar(AssistantExecutor.con(control::execute), AssistantOrchestrator.TOPE_DEL_MODELO);
    }

    private void armar(AssistantExecutor ejecutor, Duration tope) {
        reloj = new RelojDePrueba();
        store = new InMemoryChatStore();
        publicador = new Publicador();
        participantes = new Participantes();
        modelo = new Modelo();
        modo = AssistantMode.ON_MENTION;
        chat = new ChatService(store, publicador, participantes, List.of(m -> orquestador.onUserMessage(m)), reloj);
        orquestador = new AssistantOrchestrator(chat, store, publicador, modelo,
                p -> new AssistantSettings(modo, null, null), ejecutor, reloj, tope);
        ana = UUID.randomUUID();
        carlos = UUID.randomUUID();
        participantes.nombres.put(ana, "Ana Pérez");
        participantes.nombres.put(carlos, "Carlos Ruiz");
    }

    private ChatMessage escribir(UUID quien, String texto) {
        return escribirEn(PROYECTO, quien, texto, null);
    }

    private ChatMessage escribirEn(UUID proyecto, UUID quien, String texto, String replyToId) {
        return chat.postFromUser(proyecto, new StompPrincipal(quien),
                new ChatPostRequest(UUID.randomUUID().toString(), texto, null, replyToId)).message();
    }

    private List<ChatMessage> de(UUID proyecto, ChatAuthorType tipo) {
        return store.latest(proyecto.toString(), 500).stream().filter(m -> m.authorType() == tipo).toList();
    }

    private List<String> avisos() {
        return de(PROYECTO, ChatAuthorType.SYSTEM).stream().map(ChatMessage::text).toList();
    }

    private static void esperar(BooleanSupplier condicion, long ms) throws InterruptedException {
        long hasta = System.currentTimeMillis() + ms;
        while (!condicion.getAsBoolean() && System.currentTimeMillis() < hasta) {
            Thread.sleep(10);
        }
        assertThat(condicion.getAsBoolean()).as("la condición se cumplió a tiempo").isTrue();
    }

    // ------------------------------------------------------------------ modos

    @Test
    @DisplayName("OFF: nunca responde, ni con @IA; ni siquiera AI_TYPING")
    void modoOff() {
        modo = AssistantMode.OFF;

        escribir(ana, "@IA hola");

        assertThat(de(PROYECTO, ChatAuthorType.AI)).isEmpty();
        assertThat(modelo.pedidos).isEmpty();
        assertThat(publicador.escribiendo).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"@IA hola", "hola @ia", "¿@Ia, qué es UML?", "(@IA)", "pregunta para @IA."})
    @DisplayName("ON_MENTION: una mención válida de @IA dispara una respuesta")
    void mencionValida(String texto) {
        ChatMessage pregunta = escribir(ana, texto);

        assertThat(de(PROYECTO, ChatAuthorType.AI)).singleElement().satisfies(r -> {
            assertThat(r.replyToId()).isEqualTo(pregunta.id());
            assertThat(r.addressedToId()).isEqualTo(ana.toString());
            assertThat(r.kind()).isEqualTo(ChatMessageKind.ANSWER);
        });
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"correo@iaas", "juan@ia.com", "@iaas", "@@ia", "IA ayúdame", "hola a todos"})
    @DisplayName("ON_MENTION: sin mención válida no responde")
    void mencionInvalida(String texto) {
        escribir(ana, texto);

        assertThat(de(PROYECTO, ChatAuthorType.AI)).isEmpty();
        assertThat(modelo.pedidos).isEmpty();
    }

    @Test
    @DisplayName("ON_MENTION: responder a un mensaje de la IA la dispara; a uno de una persona, no")
    void respuestaAMensajeDeLaIa() {
        ChatMessage deAna = escribir(ana, "@IA ¿qué es una clase?");
        ChatMessage deLaIa = de(PROYECTO, ChatAuthorType.AI).get(0);

        escribirEn(PROYECTO, carlos, "¿y un objeto?", deLaIa.id());
        assertThat(de(PROYECTO, ChatAuthorType.AI)).hasSize(2);

        escribirEn(PROYECTO, carlos, "coincido, Ana", deAna.id());
        assertThat(de(PROYECTO, ChatAuthorType.AI)).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AssistantMode.class, names = {"ALWAYS", "MENTOR_PROACTIVE"})
    @DisplayName("ALWAYS y MENTOR_PROACTIVE: responde a todo, una sola vez (la IA no se responde a sí misma)")
    void siempre(AssistantMode m) {
        modo = m;

        escribir(ana, "hola a todos");

        assertThat(de(PROYECTO, ChatAuthorType.AI)).hasSize(1);
        assertThat(modelo.pedidos).hasSize(1);
    }

    @Test
    @DisplayName("Un mensaje de la IA o del sistema no dispara respuesta, ni en ALWAYS")
    void iaYSistemaNoDisparan() {
        modo = AssistantMode.ALWAYS;
        ChatMessage pregunta = escribir(ana, "hola");
        ChatMessage deLaIa = de(PROYECTO, ChatAuthorType.AI).get(0);
        ChatMessage delSistema = chat.postFromAi(PROYECTO, ChatAuthorType.SYSTEM, "aviso @IA", null);

        orquestador.onUserMessage(deLaIa);
        orquestador.onUserMessage(delSistema);

        assertThat(modelo.pedidos).hasSize(1);
        assertThat(de(PROYECTO, ChatAuthorType.AI)).extracting(ChatMessage::replyToId).containsExactly(pregunta.id());
    }

    // ------------------------------------------------------- una a la vez

    @Test
    @DisplayName("Una respuesta a la vez por proyecto: una sola tarea por proyecto, en orden de llegada")
    void unaALaVezPorProyecto() {
        control.retener = true;
        ChatMessage p1 = escribir(ana, "@IA uno");
        ChatMessage p2 = escribir(carlos, "@IA dos");
        ChatMessage p3 = escribir(ana, "@IA tres");
        escribirEn(OTRO, carlos, "@IA en otro proyecto", null);

        assertThat(control.retenidas).as("una tarea por proyecto, no una por mensaje").hasSize(2);
        assertThat(modelo.pedidos).isEmpty();

        control.soltar();

        assertThat(de(PROYECTO, ChatAuthorType.AI)).extracting(ChatMessage::replyToId)
                .containsExactly(p1.id(), p2.id(), p3.id());
        assertThat(de(OTRO, ChatAuthorType.AI)).hasSize(1);
    }

    @Test
    @DisplayName("Más de 5 pendientes: aviso de ocupada y esa pregunta se descarta")
    void desborde() {
        control.retener = true;
        List<ChatMessage> preguntas = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            preguntas.add(escribir(ana, "@IA pregunta " + i));
        }

        assertThat(avisos()).containsExactly(AssistantOrchestrator.OCUPADA);

        control.soltar();

        assertThat(de(PROYECTO, ChatAuthorType.AI)).extracting(ChatMessage::replyToId)
                .containsExactlyElementsOf(preguntas.subList(0, 5).stream().map(ChatMessage::id).toList());
    }

    @Test
    @DisplayName("Con hilos virtuales reales: nunca dos llamadas al modelo a la vez en un mismo proyecto")
    void concurrenciaReal() throws Exception {
        armar(AssistantExecutor.con(r -> Thread.ofVirtual().start(r)), AssistantOrchestrator.TOPE_DEL_MODELO);
        Map<String, AtomicInteger> activos = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> maximo = new ConcurrentHashMap<>();
        modelo.comportamiento = pedido -> {
            String clave = pedido.askerFirstName(); // Ana sólo en PROYECTO, Carlos sólo en OTRO
            int ahora = activos.computeIfAbsent(clave, k -> new AtomicInteger()).incrementAndGet();
            maximo.computeIfAbsent(clave, k -> new AtomicInteger()).accumulateAndGet(ahora, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            activos.get(clave).decrementAndGet();
            return new FakeChatClient().chat(pedido);
        };

        for (int i = 0; i < 3; i++) {
            escribir(ana, "@IA a" + i);
            escribirEn(OTRO, carlos, "@IA b" + i, null);
        }

        esperar(() -> de(PROYECTO, ChatAuthorType.AI).size() == 3 && de(OTRO, ChatAuthorType.AI).size() == 3, 5000);
        assertThat(maximo.get("Ana").get()).isEqualTo(1);
        assertThat(maximo.get("Carlos").get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fallos

    @ParameterizedTest(name = "{0}")
    @EnumSource(AiUnavailableException.Reason.class)
    @DisplayName("Si el modelo falla: aviso del sistema, ninguna respuesta, y AI_TYPING se apaga")
    void fallosDelModelo(AiUnavailableException.Reason motivo) {
        modelo.comportamiento = r -> {
            throw new AiUnavailableException(motivo, "detalle interno del proveedor sk-secreto");
        };

        escribir(ana, "@IA hola");

        String esperado = motivo == AiUnavailableException.Reason.PROVIDER_QUOTA
                ? AssistantOrchestrator.SIN_CUOTA
                : AssistantOrchestrator.NO_DISPONIBLE;
        assertThat(avisos()).containsExactly(esperado);
        assertThat(avisos()).noneMatch(t -> t.contains("sk-secreto") || t.contains("detalle"));
        assertThat(de(PROYECTO, ChatAuthorType.AI)).isEmpty();
        assertThat(publicador.escribiendo).containsExactly(
                new Escribiendo(PROYECTO.toString(), ana.toString(), true),
                new Escribiendo(PROYECTO.toString(), ana.toString(), false));
    }

    @Test
    @DisplayName("Un fallo inesperado también apaga AI_TYPING y avisa que no está disponible")
    void falloInesperado() {
        modelo.comportamiento = r -> {
            throw new IllegalStateException("boom");
        };

        escribir(ana, "@IA hola");

        assertThat(avisos()).containsExactly(AssistantOrchestrator.NO_DISPONIBLE);
        assertThat(publicador.escribiendo).extracting(Escribiendo::active).containsExactly(true, false);
    }

    @Test
    @DisplayName("Si el modelo tarda más que el tope: aviso, AI_TYPING apagado, y la respuesta tardía se descarta")
    void tope() throws Exception {
        armar(AssistantExecutor.con(r -> Thread.ofVirtual().start(r)), Duration.ofMillis(50));
        modelo.comportamiento = pedido -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new FakeChatClient().chat(pedido);
        };

        escribir(ana, "@IA hola");

        esperar(() -> avisos().contains(AssistantOrchestrator.NO_DISPONIBLE), 3000);
        esperar(() -> publicador.escribiendo.size() == 2, 3000);
        assertThat(publicador.escribiendo).extracting(Escribiendo::active).containsExactly(true, false);
        Thread.sleep(600);
        assertThat(de(PROYECTO, ChatAuthorType.AI)).as("la respuesta tardía no se guarda").isEmpty();
    }

    // ------------------------------------------------------------- límite

    @Test
    @DisplayName("10 llamadas por minuto por proyecto; después, un solo aviso por minuto")
    void limitePorMinuto() {
        modo = AssistantMode.ALWAYS;
        for (int i = 0; i < AssistantOrchestrator.LLAMADAS_POR_MINUTO; i++) {
            escribir(i % 2 == 0 ? ana : carlos, "mensaje " + i);
        }
        assertThat(modelo.pedidos).hasSize(10);

        escribir(ana, "el 11");
        escribir(carlos, "el 12");

        assertThat(modelo.pedidos).hasSize(10);
        assertThat(avisos()).containsExactly(AssistantOrchestrator.MUCHAS_CONSULTAS);
        assertThat(de(PROYECTO, ChatAuthorType.AI)).hasSize(10);

        reloj.avanzar(Duration.ofSeconds(61));
        escribir(ana, "ya pasó el minuto");

        assertThat(modelo.pedidos).hasSize(11);
        assertThat(de(PROYECTO, ChatAuthorType.AI)).hasSize(11);
    }

    @Test
    @DisplayName("El límite es por proyecto: agotarlo en uno no afecta a otro")
    void limitePorProyecto() {
        modo = AssistantMode.ALWAYS;
        for (int i = 0; i < 11; i++) {
            escribir(i % 2 == 0 ? ana : carlos, "m" + i);
        }

        escribirEn(OTRO, ana, "en otro proyecto", null);

        assertThat(de(OTRO, ChatAuthorType.AI)).hasSize(1);
    }

    // -------------------------------------------- contexto y forma de la respuesta

    @Test
    @DisplayName("Lo que recibe el modelo: el hilo de Ana no trae el de Carlos")
    void hilosNoSeMezclan() {
        escribir(ana, "@IA ¿qué es la herencia?");
        escribir(carlos, "@IA ¿qué es la agregación?");
        escribir(ana, "@IA ¿y la composición?");

        ChatRequest ultimo = modelo.pedidos.get(2);
        String sys = ultimo.system();
        String hilo = sys.substring(sys.indexOf("<<<"), sys.indexOf(">>>"));

        assertThat(ultimo.askerFirstName()).isEqualTo("Ana");
        assertThat(hilo).contains("herencia", "Hola Ana").doesNotContain("agregación", "Carlos");
        assertThat(ultimo.turns().get(ultimo.turns().size() - 1).content())
                .isEqualTo("[Ana Pérez]: @IA ¿y la composición?");
    }

    @Test
    @DisplayName("La respuesta empieza dirigida a quien preguntó (AddressingGuard), y queda tal cual si ya lo estaba")
    void dirigidaAQuienPregunto() {
        modelo.comportamiento = r -> new ChatCompletion("Una composición es una relación fuerte.", "m");
        escribir(carlos, "@IA ¿qué es una composición?");

        modelo.comportamiento = new FakeChatClient()::chat;
        escribir(ana, "@IA hola");

        assertThat(de(PROYECTO, ChatAuthorType.AI)).extracting(ChatMessage::text)
                .containsExactly("Carlos, Una composición es una relación fuerte.", "Hola Ana, (respuesta simulada)");
        assertThat(de(PROYECTO, ChatAuthorType.AI)).extracting(ChatMessage::addressedToName)
                .containsExactly("Carlos Ruiz", "Ana Pérez");
    }

    @Test
    @DisplayName("AI_TYPING se enciende y apaga para quien preguntó, alrededor de la respuesta")
    void typingAlrededor() {
        escribir(ana, "@IA hola");

        assertThat(publicador.escribiendo).containsExactly(
                new Escribiendo(PROYECTO.toString(), ana.toString(), true),
                new Escribiendo(PROYECTO.toString(), ana.toString(), false));
        // El orden en el broker: la respuesta de la IA se difunde antes del "off".
        assertThat(publicador.difundidos).extracting(ChatMessage::authorType)
                .containsExactly(ChatAuthorType.USER, ChatAuthorType.AI);
    }
}
