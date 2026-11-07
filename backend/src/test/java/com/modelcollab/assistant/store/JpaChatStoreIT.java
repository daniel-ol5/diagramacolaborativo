package com.modelcollab.assistant.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.assistant.chat.ChatHistoryQuery;
import com.modelcollab.assistant.chat.ChatHistoryStore;
import com.modelcollab.assistant.chat.ChatReadState;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CU19, etapa 1B: {@link JpaChatStore} contra un <b>PostgreSQL real</b>. Opcional: se salta si no
 * existe {@code MC_TEST_PG_URL}, así que {@code ./mvnw clean test} lo compila pero no lo corre
 * (además, el sufijo {@code IT} queda fuera de los patrones por defecto de Surefire).
 *
 * <h2>Cómo correrlo</h2>
 *
 * <pre>
 * # una base VACÍA y descartable, con "test" en el nombre:
 * #   createdb modelcollab_chat_test
 * $env:MC_TEST_PG_URL="jdbc:postgresql://localhost:5432/modelcollab_chat_test"
 * $env:MC_TEST_PG_USER="..."
 * $env:MC_TEST_PG_PASSWORD="..."
 * ./mvnw test -Dtest=JpaChatStoreIT
 * </pre>
 *
 * <h2>Por qué sólo una base descartable</h2>
 *
 * <p>El test aplica Flyway (V1 a V4) sobre esa base, y crea y borra filas. Para que nunca toque
 * la base de verdad, antes de levantar Spring se niega a seguir si: el nombre de la base no
 * contiene "test"; la base tiene tablas pero no es de ModelCollab; o tiene usuarios que no creó
 * este test. Ninguna propiedad sale de {@code application.yml} —no se carga— ni de valores
 * escritos acá: todo viene de las tres variables de entorno.
 */
class JpaChatStoreIT {

    private static final String VAR_URL = "MC_TEST_PG_URL";
    private static final String VAR_USUARIO = "MC_TEST_PG_USER";
    private static final String VAR_CLAVE = "MC_TEST_PG_PASSWORD";

    /** Dominio reservado (RFC 2606): marca los usuarios que crea este test. */
    private static final String DOMINIO_DE_PRUEBA = "@chat-it.invalid";

    private static ConfigurableApplicationContext contexto;
    private static ChatStore store;
    private static ChatHistoryStore historial;
    private static JdbcTemplate jdbc;

    /** Lo mínimo para JpaChatStore: DataSource, Flyway, JPA y transacciones. Sin web ni seguridad. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @Import(JpaChatStore.class)
    static class Contexto {}

    @BeforeAll
    static void levantar() throws SQLException {
        String url = System.getenv(VAR_URL);
        Assumptions.assumeTrue(
                url != null && !url.isBlank(),
                VAR_URL + " no está definida: se salta el test contra PostgreSQL real");
        String usuario = System.getenv(VAR_USUARIO);
        String clave = System.getenv(VAR_CLAVE);

        // Antes de Spring, porque Spring corre Flyway al arrancar.
        exigirBaseDescartable(url, usuario, clave);

        Map<String, Object> propiedades = new HashMap<>();
        // Ningún application*.yml: así ningún valor por defecto del proyecto (por ejemplo la URL
        // de la base de desarrollo) puede colarse.
        propiedades.put("spring.config.name", "jpa-chat-store-it-sin-config");
        propiedades.put("spring.main.banner-mode", "off");
        propiedades.put("spring.datasource.url", url);
        if (usuario != null) {
            propiedades.put("spring.datasource.username", usuario);
        }
        if (clave != null) {
            propiedades.put("spring.datasource.password", clave);
        }
        // Lo mismo que producción: si la entidad y V4 difieren, el contexto no arranca.
        propiedades.put("spring.jpa.hibernate.ddl-auto", "validate");
        propiedades.put("spring.jpa.open-in-view", "false");
        propiedades.put("spring.flyway.enabled", "true");
        propiedades.put("spring.flyway.locations", "classpath:db/migration");
        // Sin baseline: sobre una base con tablas ajenas, Flyway tiene que fallar, no adoptarlas.
        propiedades.put("spring.flyway.baseline-on-migrate", "false");
        propiedades.put("spring.flyway.clean-disabled", "true");
        propiedades.put("modelcollab.chat.store", "postgres");

        // addFirst: estas propiedades le ganan a cualquier variable de entorno del sistema,
        // como un SPRING_DATASOURCE_URL que apunte a la base de desarrollo.
        StandardEnvironment entorno = new StandardEnvironment();
        entorno.getPropertySources().addFirst(new MapPropertySource("jpa-chat-store-it", propiedades));

        contexto = new SpringApplicationBuilder(Contexto.class)
                .environment(entorno)
                .web(WebApplicationType.NONE)
                .run();
        store = contexto.getBean(ChatStore.class);
        historial = contexto.getBean(ChatHistoryStore.class);
        jdbc = contexto.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void cerrar() {
        if (contexto != null) {
            contexto.close();
        }
    }

    // ------------------------------------------------------------ datos

    private final List<UUID> usuariosCreados = new ArrayList<>();
    private UUID proyecto;
    private UUID otroProyecto;
    private UUID diagrama;

    @BeforeEach
    void crearDatos() {
        proyecto = crearProyecto();
        otroProyecto = crearProyecto();
        diagrama = UUID.randomUUID();
        jdbc.update("INSERT INTO diagrams (id, project_id, name) VALUES (?, ?, ?)",
                diagrama, proyecto, "Diagrama IT");
    }

    /** Borrar el usuario dueño arrastra, por cascada, sus proyectos, diagramas y todo el chat. */
    @AfterEach
    void limpiar() {
        for (UUID usuario : usuariosCreados) {
            jdbc.update("DELETE FROM users WHERE id = ?", usuario);
        }
    }

    private UUID crearProyecto() {
        UUID dueno = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                dueno, dueno + DOMINIO_DE_PRUEBA, "no-es-un-hash", "Usuario IT");
        usuariosCreados.add(dueno);
        jdbc.update("INSERT INTO projects (id, name, owner_id) VALUES (?, ?, ?)",
                id, "Proyecto IT", dueno);
        return id;
    }

    private static ChatMessage mensaje(UUID proyecto, long seq, String clientMessageId) {
        return mensaje(proyecto, seq, clientMessageId, null, null);
    }

    private static ChatMessage mensaje(
            UUID proyecto, long seq, String clientMessageId, UUID diagrama, String replyToId) {
        return new ChatMessage(
                UUID.randomUUID().toString(), proyecto.toString(),
                diagrama == null ? null : diagrama.toString(), seq, ChatAuthorType.USER,
                UUID.randomUUID().toString(), "Ana Pérez", "#D55E00", null, null, replyToId,
                clientMessageId, ChatMessageKind.CHAT, "mensaje " + seq,
                Instant.parse("2026-11-04T10:15:30.123456Z"), null);
    }

    private long contar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ esquema

    @Test
    @DisplayName("Flyway aplica V1 a V5 y Hibernate valida la entidad contra V4 (ddl-auto validate)")
    void esquemaValidado() {
        // Que el contexto haya arrancado ya es la prueba de la validación: con ddl-auto=validate,
        // una columna faltante o de otro tipo hace fallar el arranque en levantar().
        assertThat(contexto.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("validate");
        List<String> versiones = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);
        assertThat(versiones).containsSequence("1", "2", "3", "4", "5");
    }

    // ------------------------------------------------- estado de lectura (CU23, V5)

    private UUID crearUsuario() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                id, id + DOMINIO_DE_PRUEBA, "no-es-un-hash", "Lector IT");
        usuariosCreados.add(id);
        return id;
    }

    private ChatMessage guardarDe(UUID proyecto, UUID autor, ChatAuthorType tipo) {
        long seq = store.nextSeq(proyecto.toString());
        return store.save(new ChatMessage(
                UUID.randomUUID().toString(), proyecto.toString(), null, seq, tipo,
                autor == null ? null : autor.toString(), tipo == ChatAuthorType.USER ? "Ana" : "IA",
                null, null, null, null, null,
                tipo == ChatAuthorType.SYSTEM ? ChatMessageKind.SYSTEM : ChatMessageKind.CHAT,
                "texto " + seq, Instant.now(), null));
    }

    @Test
    @DisplayName("V5: chat_read_state existe con su clave primaria (project_id, user_id)")
    void v5Aplicada() {
        List<String> pk = jdbc.queryForList(
                """
                SELECT a.attname FROM pg_index i
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                 WHERE i.indrelid = 'chat_read_state'::regclass AND i.indisprimary
                """,
                String.class);
        assertThat(pk).containsExactlyInAnyOrder("project_id", "user_id");
    }

    @Test
    @DisplayName("readState y markRead: usuario nuevo en 0, no cuenta los propios, solo avanza y recorta")
    void estadoDeLectura() {
        UUID ana = crearUsuario();
        UUID carlos = crearUsuario();
        assertThat(historial.readState(proyecto, ana)).isEqualTo(new ChatReadState(0, 0, 0));

        guardarDe(proyecto, ana, ChatAuthorType.USER);       // 1, propio
        guardarDe(proyecto, carlos, ChatAuthorType.USER);    // 2
        guardarDe(proyecto, null, ChatAuthorType.AI);        // 3
        guardarDe(proyecto, null, ChatAuthorType.SYSTEM);    // 4
        guardarDe(proyecto, ana, ChatAuthorType.USER);       // 5, propio

        assertThat(historial.readState(proyecto, ana)).isEqualTo(new ChatReadState(0, 3, 5));
        assertThat(historial.markRead(proyecto, ana, 3)).isEqualTo(new ChatReadState(3, 1, 5));
        assertThat(historial.markRead(proyecto, ana, 1)).as("no retrocede").isEqualTo(new ChatReadState(3, 1, 5));
        assertThat(historial.markRead(proyecto, ana, 3)).as("idempotente").isEqualTo(new ChatReadState(3, 1, 5));
        assertThat(historial.markRead(proyecto, ana, 999)).as("recorta a lastSeq").isEqualTo(new ChatReadState(5, 0, 5));
        assertThat(contar("SELECT last_read_seq FROM chat_read_state WHERE project_id = ? AND user_id = ?",
                proyecto, ana)).isEqualTo(5);
        assertThat(historial.readState(otroProyecto, ana)).as("por proyecto").isEqualTo(new ChatReadState(0, 0, 0));
    }

    @Test
    @DisplayName("markRead sin mensajes deja la fila en 0")
    void marcarSinMensajes() {
        UUID ana = crearUsuario();
        assertThat(historial.markRead(proyecto, ana, 10)).isEqualTo(new ChatReadState(0, 0, 0));
        assertThat(contar("SELECT COUNT(*) FROM chat_read_state WHERE project_id = ?", proyecto)).isEqualTo(1);
    }

    @Test
    @DisplayName("markRead con varios hilos: upsert atómico, una sola fila y queda el máximo recortado")
    void markReadConcurrente() throws Exception {
        UUID ana = crearUsuario();
        for (int i = 0; i < 20; i++) {
            guardarDe(proyecto, null, ChatAuthorType.AI);
        }
        int hilos = 8;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<ChatReadState>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            long hasta = i == 3 ? 500 : i * 2;  // uno pide un futuro
            tareas.add(pool.submit(() -> {
                largada.await();
                return historial.markRead(proyecto, ana, hasta);
            }));
        }
        largada.countDown();
        for (Future<ChatReadState> t : tareas) {
            assertThat(t.get().lastReadSeq()).isBetween(0L, 20L);
        }
        pool.shutdown();

        assertThat(contar("SELECT COUNT(*) FROM chat_read_state WHERE project_id = ? AND user_id = ?",
                proyecto, ana)).isEqualTo(1);
        assertThat(historial.readState(proyecto, ana)).isEqualTo(new ChatReadState(20, 0, 20));
    }

    @Test
    @DisplayName("Borrar el proyecto o el usuario borra su chat_read_state (ON DELETE CASCADE)")
    void cascada() {
        UUID ana = crearUsuario();
        UUID carlos = crearUsuario();
        guardarDe(proyecto, null, ChatAuthorType.AI);
        guardarDe(otroProyecto, null, ChatAuthorType.AI);
        historial.markRead(proyecto, ana, 1);
        historial.markRead(proyecto, carlos, 1);
        historial.markRead(otroProyecto, ana, 1);

        jdbc.update("DELETE FROM users WHERE id = ?", carlos);
        assertThat(contar("SELECT COUNT(*) FROM chat_read_state WHERE user_id = ?", carlos)).isZero();

        jdbc.update("DELETE FROM projects WHERE id = ?", proyecto);
        assertThat(contar("SELECT COUNT(*) FROM chat_read_state WHERE project_id = ?", proyecto)).isZero();
        assertThat(contar("SELECT COUNT(*) FROM chat_read_state WHERE project_id = ? AND user_id = ?",
                otroProyecto, ana)).as("lo de otro proyecto queda").isEqualTo(1);
    }

    // ------------------------------------------------- búsqueda y filtros (CU23)

    private long guardarTexto(UUID proyecto, ChatAuthorType tipo, ChatMessageKind clase, String texto) {
        long seq = store.nextSeq(proyecto.toString());
        store.save(new ChatMessage(
                UUID.randomUUID().toString(), proyecto.toString(), null, seq, tipo,
                tipo == ChatAuthorType.USER ? UUID.randomUUID().toString() : null,
                tipo == ChatAuthorType.USER ? "Ana" : "IA", null, null, null, null, null,
                clase, texto, Instant.now(), null));
        return seq;
    }

    private List<Long> buscar(UUID proyecto, Long antes, Long despues, int limit, String q,
            ChatAuthorType tipo, ChatMessageKind clase) {
        return historial.find(new ChatHistoryQuery(proyecto, antes, despues, limit, q, tipo, clase))
                .stream().map(ChatMessage::seq).toList();
    }

    @Test
    @DisplayName("ILIKE: sin mayúsculas, prefijos, solo en el proyecto y ascendente")
    void busquedaIlike() {
        long uno = guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "Agreguen los ATRIBUTOS");
        guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "nada que ver");
        long tres = guardarTexto(proyecto, ChatAuthorType.AI, ChatMessageKind.ANSWER, "los atributos van con tipo");
        guardarTexto(otroProyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "atributos ajenos");

        assertThat(buscar(proyecto, null, null, 10, "atrib", null, null)).containsExactly(uno, tres);
    }

    @Test
    @DisplayName("Comodines del usuario literales en PostgreSQL: %, _, barra invertida y !")
    void busquedaEscapada() {
        long porciento = guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "al 100% listo");
        guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "al 100 listo");
        long guion = guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "fecha_alta");
        guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "fechaXalta");
        long ruta = guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "C:\\temp\\x");
        guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "C:temp");
        long admiracion = guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "hola!!");

        assertThat(buscar(proyecto, null, null, 10, "0%", null, null)).containsExactly(porciento);
        assertThat(buscar(proyecto, null, null, 10, "%%", null, null)).isEmpty();
        assertThat(buscar(proyecto, null, null, 10, "fecha_alta", null, null)).containsExactly(guion);
        assertThat(buscar(proyecto, null, null, 10, "C:\\temp", null, null)).containsExactly(ruta);
        assertThat(buscar(proyecto, null, null, 10, "a!!", null, null)).containsExactly(admiracion);
        assertThat(buscar(proyecto, null, null, 10, "!", null, null)).containsExactly(admiracion);
    }

    @Test
    @DisplayName("Filtros por authorType y kind, combinados con cursores, en PostgreSQL")
    void filtrosYCursores() {
        for (int i = 1; i <= 12; i++) {
            if (i % 2 == 0) {
                guardarTexto(proyecto, ChatAuthorType.AI,
                        i % 4 == 0 ? ChatMessageKind.SUMMARY : ChatMessageKind.ANSWER, "ia " + i);
            } else {
                guardarTexto(proyecto, ChatAuthorType.USER, ChatMessageKind.CHAT, "ana " + i);
            }
        }

        assertThat(buscar(proyecto, null, null, 3, null, ChatAuthorType.AI, null)).containsExactly(8L, 10L, 12L);
        assertThat(buscar(proyecto, 8L, null, 3, null, ChatAuthorType.AI, null)).containsExactly(2L, 4L, 6L);
        assertThat(buscar(proyecto, null, 5L, 2, null, ChatAuthorType.AI, null)).containsExactly(6L, 8L);
        assertThat(buscar(proyecto, null, null, 10, null, null, ChatMessageKind.SUMMARY)).containsExactly(4L, 8L, 12L);
        assertThat(buscar(proyecto, null, null, 10, "ia 1", ChatAuthorType.AI, ChatMessageKind.SUMMARY))
                .containsExactly(12L);
        assertThat(buscar(proyecto, null, 0L, 10, "ana 1", ChatAuthorType.USER, null)).containsExactly(1L, 11L);
    }

    // ------------------------------------------------------------ nextSeq

    @Test
    @DisplayName("nextSeq con varios hilos sobre el mismo proyecto: sin huecos ni repetidos")
    void nextSeqConcurrente() throws Exception {
        int hilos = 4;
        int porHilo = 50;
        Set<Long> vistos = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(pool.submit(() -> {
                largada.await();
                boolean todosNuevos = true;
                for (int j = 0; j < porHilo; j++) {
                    todosNuevos &= vistos.add(store.nextSeq(proyecto.toString()));
                }
                return todosNuevos;
            }));
        }
        largada.countDown();
        for (Future<Boolean> t : tareas) {
            assertThat(t.get()).as("ningún seq se repitió").isTrue();
        }
        pool.shutdown();

        assertThat(vistos).containsExactlyInAnyOrderElementsOf(
                LongStream.rangeClosed(1, (long) hilos * porHilo).boxed().toList());
        assertThat(contar("SELECT last_seq FROM chat_counters WHERE project_id = ?", proyecto))
                .isEqualTo((long) hilos * porHilo);
        assertThat(store.nextSeq(otroProyecto.toString())).as("cada proyecto cuenta aparte").isEqualTo(1);
    }

    // ------------------------------------------------------------ lecturas

    @Test
    @DisplayName("save y las lecturas: ida y vuelta exacta, y latest/before/after en orden ascendente")
    void guardaYLeeAscendente() {
        ChatMessage conMeta = new ChatMessage(
                UUID.randomUUID().toString(), proyecto.toString(), diagrama.toString(), 3,
                ChatAuthorType.AI, null, "IA", null, UUID.randomUUID().toString(), "Ana Pérez",
                null, "ai-3", ChatMessageKind.ANSWER, "Ana, se dibuja con rombo lleno.",
                Instant.parse("2026-11-04T10:15:30.123456Z"),
                Map.of("modo", "ON_MENTION", "intentos", 2));
        // Desordenados a propósito: el orden lo tiene que dar seq, no el orden de inserción.
        store.save(conMeta);
        for (long seq : new long[] {1, 5, 2, 4}) {
            store.save(mensaje(proyecto, seq, "c-" + seq));
        }

        assertThat(store.findById(proyecto.toString(), conMeta.id())).contains(conMeta);
        assertThat(store.findByClientMessageId(proyecto.toString(), "ai-3")).contains(conMeta);
        assertThat(store.latest(proyecto.toString(), 10)).extracting(ChatMessage::seq)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(store.latest(proyecto.toString(), 3)).extracting(ChatMessage::seq)
                .containsExactly(3L, 4L, 5L);
        assertThat(store.before(proyecto.toString(), 4, 2)).extracting(ChatMessage::seq)
                .containsExactly(2L, 3L);
        assertThat(store.after(proyecto.toString(), 2, 2)).extracting(ChatMessage::seq)
                .containsExactly(3L, 4L);
        assertThat(store.after(proyecto.toString(), 5, 10)).isEmpty();
    }

    @Test
    @DisplayName("Las lecturas no cruzan proyectos, y un id que no es UUID no existe")
    void aisladoPorProyecto() {
        ChatMessage m = store.save(mensaje(proyecto, 1, "c-1"));

        assertThat(store.findById(otroProyecto.toString(), m.id())).isEmpty();
        assertThat(store.findByClientMessageId(otroProyecto.toString(), "c-1")).isEmpty();
        assertThat(store.latest(otroProyecto.toString(), 10)).isEmpty();
        assertThat(store.findById(proyecto.toString(), "no-es-un-uuid")).isEmpty();
    }

    // ------------------------------------------------------------ índices únicos

    @Test
    @DisplayName("Índice único de client_message_id: el choque llega como DuplicateKeyException")
    void duplicadoDeCliente() {
        store.save(mensaje(proyecto, 1, "c-1"));

        assertThatThrownBy(() -> store.save(mensaje(proyecto, 2, "c-1")))
                .isInstanceOf(DuplicateKeyException.class);
        // El mismo id en otro proyecto no choca, y los mensajes sin id de cliente tampoco.
        store.save(mensaje(otroProyecto, 1, "c-1"));
        store.save(mensaje(proyecto, 3, null));
        store.save(mensaje(proyecto, 4, null));
        assertThat(store.latest(proyecto.toString(), 10)).extracting(ChatMessage::seq)
                .containsExactly(1L, 3L, 4L);
    }

    @Test
    @DisplayName("Índice único de (project_id, seq): el choque NO se disfraza de duplicado")
    void colisionDeSeqNoEsDuplicado() {
        store.save(mensaje(proyecto, 1, "c-a"));

        assertThatThrownBy(() -> store.save(mensaje(proyecto, 1, "c-b")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------ claves foráneas

    @Test
    @DisplayName("Borrar el proyecto borra en cascada sus mensajes y su contador")
    void cascadaAlBorrarProyecto() {
        store.nextSeq(proyecto.toString());
        store.save(mensaje(proyecto, 1, "c-1"));
        store.save(mensaje(proyecto, 2, "c-2"));
        store.save(mensaje(otroProyecto, 1, "c-1"));

        jdbc.update("DELETE FROM projects WHERE id = ?", proyecto);

        assertThat(contar("SELECT count(*) FROM chat_messages WHERE project_id = ?", proyecto)).isZero();
        assertThat(contar("SELECT count(*) FROM chat_counters WHERE project_id = ?", proyecto)).isZero();
        assertThat(store.latest(otroProyecto.toString(), 10)).as("el otro proyecto no se toca").hasSize(1);
    }

    @Test
    @DisplayName("FK de diagram_id: borrar el diagrama deja el mensaje con diagramId null; uno inexistente se rechaza")
    void fkDiagrama() {
        ChatMessage m = store.save(mensaje(proyecto, 1, "c-1", diagrama, null));
        assertThat(m.diagramId()).isEqualTo(diagrama.toString());

        jdbc.update("DELETE FROM diagrams WHERE id = ?", diagrama);

        assertThat(store.findById(proyecto.toString(), m.id()))
                .hasValueSatisfying(leido -> assertThat(leido.diagramId()).isNull());
        assertThatThrownBy(() -> store.save(mensaje(proyecto, 2, "c-2", UUID.randomUUID(), null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("FK de reply_to_id: borrar el mensaje original deja la respuesta con replyToId null; uno inexistente se rechaza")
    void fkReplyTo() {
        ChatMessage original = store.save(mensaje(proyecto, 1, "c-1"));
        ChatMessage respuesta = store.save(mensaje(proyecto, 2, "c-2", null, original.id()));
        assertThat(respuesta.replyToId()).isEqualTo(original.id());

        jdbc.update("DELETE FROM chat_messages WHERE id = ?", UUID.fromString(original.id()));

        assertThat(store.findById(proyecto.toString(), respuesta.id()))
                .hasValueSatisfying(leido -> assertThat(leido.replyToId()).isNull());
        assertThatThrownBy(() -> store.save(
                        mensaje(proyecto, 3, "c-3", null, UUID.randomUUID().toString())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------ protección

    /**
     * Se niega a seguir si la base no parece descartable. Va con JDBC crudo y antes de Spring,
     * porque cuando Spring arranca Flyway ya migró.
     */
    private static void exigirBaseDescartable(String url, String usuario, String clave)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url, usuario, clave)) {
            String base = c.getCatalog();
            if (base == null || !base.toLowerCase(Locale.ROOT).contains("test")) {
                throw new IllegalStateException(
                        "JpaChatStoreIT sólo corre sobre una base descartable con \"test\" en el"
                                + " nombre; " + VAR_URL + " apunta a '" + base + "'");
            }
            List<String> tablas = new ArrayList<>();
            try (ResultSet rs = c.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables"
                            + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
                while (rs.next()) {
                    tablas.add(rs.getString(1));
                }
            }
            if (tablas.isEmpty()) {
                return; // base vacía: es exactamente lo que se pide
            }
            if (!tablas.contains("flyway_schema_history")) {
                throw new IllegalStateException(
                        "La base '" + base + "' tiene tablas que no son de ModelCollab: " + tablas);
            }
            if (tablas.contains("users")) {
                try (var ps = c.prepareStatement(
                        "SELECT count(*) FROM users WHERE email NOT LIKE ?")) {
                    ps.setString(1, "%" + DOMINIO_DE_PRUEBA);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            throw new IllegalStateException(
                                    "La base '" + base + "' tiene usuarios reales; usá una vacía");
                        }
                    }
                }
            }
        }
    }
}
