package com.modelcollab.assistant.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatStore;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CU19, etapa 1B: lo de {@link JpaChatStore} que se puede probar <b>sin base de datos</b> — el
 * mapeo entre el dominio y la fila, el reconocimiento de duplicados y qué almacenamiento se
 * carga según {@code modelcollab.chat.store}. Lo que necesita un PostgreSQL de verdad (FK,
 * índices, ON CONFLICT) está en {@code JpaChatStoreIT}.
 */
class JpaChatStoreMapeoTest {

    private static ChatMessage completo() {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                42,
                ChatAuthorType.AI,
                UUID.randomUUID().toString(),
                "IA",
                "#006CD1",
                UUID.randomUUID().toString(),
                "Ana Pérez",
                UUID.randomUUID().toString(),
                "ai-reply:123",
                ChatMessageKind.ANSWER,
                "Ana, una composición se dibuja con rombo lleno.",
                // Con microsegundos y sin nanos: es la precisión que guarda TIMESTAMPTZ.
                Instant.parse("2026-11-04T10:15:30.123456Z"),
                Map.of("modo", "ON_MENTION", "intentos", 2, "modelos", List.of("a", "b")));
    }

    private static ChatMessage minimo() {
        return new ChatMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 1,
                ChatAuthorType.SYSTEM, null, "Sistema", null, null, null, null, null,
                ChatMessageKind.SYSTEM, "La IA no está disponible ahora",
                Instant.parse("2026-11-04T10:15:30Z"), null);
    }

    @Nested
    @DisplayName("Mapeo dominio <-> entidad")
    class Mapeo {

        @Test
        @DisplayName("Ida y vuelta conserva todos los campos, meta incluido")
        void idaYVueltaCompleta() {
            ChatMessage original = completo();

            assertThat(JpaChatStore.aDominio(JpaChatStore.aFila(original))).isEqualTo(original);
        }

        @Test
        @DisplayName("Ida y vuelta con todos los opcionales en null")
        void idaYVueltaMinima() {
            ChatMessage original = minimo();

            assertThat(JpaChatStore.aDominio(JpaChatStore.aFila(original))).isEqualTo(original);
        }

        @Test
        @DisplayName("Los ids de texto se guardan como UUID y los enums tal cual")
        void tiposDeLaFila() {
            ChatMessage m = completo();
            ChatMessageEntity fila = JpaChatStore.aFila(m);

            assertThat(fila.getId()).isEqualTo(UUID.fromString(m.id()));
            assertThat(fila.getProjectId()).isEqualTo(UUID.fromString(m.projectId()));
            assertThat(fila.getDiagramId()).isEqualTo(UUID.fromString(m.diagramId()));
            assertThat(fila.getReplyToId()).isEqualTo(UUID.fromString(m.replyToId()));
            assertThat(fila.getAuthorType()).isEqualTo(ChatAuthorType.AI);
            assertThat(fila.getKind()).isEqualTo(ChatMessageKind.ANSWER);
            assertThat(fila.getSeq()).isEqualTo(42);
        }

        @Test
        @DisplayName("created_at se guarda en UTC y truncado a microsegundos")
        void creadoEnUtcYMicros() {
            ChatMessage conNanos =
                    conCreado(minimo(), Instant.parse("2026-11-04T10:15:30.123456789Z"));

            ChatMessageEntity fila = JpaChatStore.aFila(conNanos);

            assertThat(fila.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(JpaChatStore.aDominio(fila).createdAt())
                    .isEqualTo(Instant.parse("2026-11-04T10:15:30.123456Z"));
        }

        @Test
        @DisplayName("Sin createdAt se usa la hora actual: la columna es NOT NULL")
        void sinCreado() {
            Instant antes = Instant.now().minusSeconds(1);

            ChatMessageEntity fila = JpaChatStore.aFila(conCreado(minimo(), null));

            assertThat(fila.getCreatedAt().toInstant()).isAfter(antes);
        }

        @Test
        @DisplayName("meta vacío se guarda como NULL y se lee como null")
        void metaVacio() {
            ChatMessage m = completo();
            ChatMessage sinMeta = new ChatMessage(
                    m.id(), m.projectId(), m.diagramId(), m.seq(), m.authorType(), m.authorId(),
                    m.authorName(), m.authorColor(), m.addressedToId(), m.addressedToName(),
                    m.replyToId(), m.clientMessageId(), m.kind(), m.text(), m.createdAt(), Map.of());

            ChatMessageEntity fila = JpaChatStore.aFila(sinMeta);

            assertThat(fila.getMeta()).isNull();
            assertThat(JpaChatStore.aDominio(fila).meta()).isNull();
        }

        @Test
        @DisplayName("meta con un campo que nadie declaró se lee igual (no es el mapper canónico)")
        void metaConCamposLibres() {
            ChatMessage m = completo();
            ChatMessageEntity fila = JpaChatStore.aFila(m);

            assertThat(fila.getMeta()).contains("\"modo\":\"ON_MENTION\"");
            assertThat(JpaChatStore.aDominio(fila).meta())
                    .containsEntry("intentos", 2)
                    .containsEntry("modelos", List.of("a", "b"));
        }

        private static ChatMessage conCreado(ChatMessage m, Instant creado) {
            return new ChatMessage(
                    m.id(), m.projectId(), m.diagramId(), m.seq(), m.authorType(), m.authorId(),
                    m.authorName(), m.authorColor(), m.addressedToId(), m.addressedToName(),
                    m.replyToId(), m.clientMessageId(), m.kind(), m.text(), creado, m.meta());
        }
    }

    @Nested
    @DisplayName("Reconocer el duplicado de clientMessageId")
    class Duplicados {

        private static final String SEQ = "uq_chat_messages_project_seq";

        @Test
        @DisplayName("La violación del índice de client_message_id es un duplicado")
        void indiceDeCliente() {
            var cve = new org.hibernate.exception.ConstraintViolationException(
                    "dup", new SQLException("dup", "23505"), JpaChatStore.INDICE_CLIENT_MESSAGE);

            assertThat(JpaChatStore.esDuplicadoDeCliente(cve)).isTrue();
            assertThat(JpaChatStore.esDuplicadoDeCliente(new PersistenceException("envuelta", cve)))
                    .isTrue();
        }

        @Test
        @DisplayName("Se reconoce también por el SQLException crudo, con SQLSTATE 23505")
        void porSqlException() {
            SQLException sql = new SQLException(
                    "duplicate key value violates unique constraint \""
                            + JpaChatStore.INDICE_CLIENT_MESSAGE + "\"",
                    "23505");

            assertThat(JpaChatStore.esDuplicadoDeCliente(new PersistenceException(sql))).isTrue();
        }

        @Test
        @DisplayName("Una colisión de (project_id, seq) NO se disfraza de duplicado: es unicidad a envolver")
        void colisionDeSeq() {
            var cve = new org.hibernate.exception.ConstraintViolationException(
                    "dup", new SQLException("dup", "23505"), SEQ);
            SQLException cruda = new SQLException("duplicate key \"" + SEQ + "\"", "23505");

            assertThat(JpaChatStore.esDuplicadoDeCliente(cve)).isFalse();
            assertThat(JpaChatStore.esDuplicadoDeCliente(cruda)).isFalse();
            // Y sí se reconoce como violación de unicidad: es la rama de save() que la envuelve
            // en DataIntegrityViolationException para que el proxy no la convierta en
            // DuplicateKeyException.
            assertThat(JpaChatStore.esViolacionDeUnicidad(cve)).isTrue();
            assertThat(JpaChatStore.esViolacionDeUnicidad(new PersistenceException(cve))).isTrue();
            assertThat(JpaChatStore.esViolacionDeUnicidad(new PersistenceException(cruda))).isTrue();
        }

        @Test
        @DisplayName("Una FK violada u otro error no es un duplicado ni una violación de unicidad")
        void otrosErrores() {
            SQLException fk = new SQLException("violates foreign key constraint", "23503");
            var cveFk = new org.hibernate.exception.ConstraintViolationException(
                    "fk", fk, "chat_messages_diagram_id_fkey");

            assertThat(JpaChatStore.esDuplicadoDeCliente(fk)).isFalse();
            assertThat(JpaChatStore.esDuplicadoDeCliente(new IllegalStateException("x"))).isFalse();
            // Las FK siguen su traducción normal: save() no las envuelve.
            assertThat(JpaChatStore.esViolacionDeUnicidad(fk)).isFalse();
            assertThat(JpaChatStore.esViolacionDeUnicidad(new PersistenceException(cveFk))).isFalse();
            assertThat(JpaChatStore.esViolacionDeUnicidad(new IllegalStateException("x"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Selección del almacenamiento por modelcollab.chat.store")
    class Seleccion {

        private final ApplicationContextRunner contexto =
                new ApplicationContextRunner()
                        .withUserConfiguration(InMemoryChatStore.class, JpaChatStore.class);

        @Test
        @DisplayName("memory carga InMemoryChatStore y no necesita DataSource ni JPA")
        void memory() {
            // Sin JdbcTemplate, sin EntityManagerFactory y sin DataSource en el contexto: si
            // JpaChatStore se intentara crear, el contexto no arrancaría.
            contexto.withPropertyValues("modelcollab.chat.store=memory")
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx).hasSingleBean(ChatStore.class);
                        assertThat(ctx).hasSingleBean(InMemoryChatStore.class);
                        assertThat(ctx).doesNotHaveBean(JpaChatStore.class);
                        assertThat(ctx).doesNotHaveBean(DataSource.class);
                    });
        }

        @Test
        @DisplayName("postgres carga JpaChatStore")
        void postgres() {
            conJpaSimulado()
                    .withPropertyValues("modelcollab.chat.store=postgres")
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx).hasSingleBean(ChatStore.class);
                        assertThat(ctx).hasSingleBean(JpaChatStore.class);
                    });
        }

        @Test
        @DisplayName("Sin la propiedad, el valor por defecto es JpaChatStore")
        void porDefecto() {
            conJpaSimulado().run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(JpaChatStore.class);
                assertThat(ctx).doesNotHaveBean(InMemoryChatStore.class);
            });
        }

        @Test
        @DisplayName("application.yml declara postgres como valor por defecto de CHAT_STORE")
        void defaultDelYml() throws Exception {
            List<PropertySource<?>> fuentes = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));

            assertThat(fuentes).isNotEmpty();
            assertThat(fuentes.get(0).getProperty("modelcollab.chat.store"))
                    .hasToString("${CHAT_STORE:postgres}");
        }

        /**
         * JdbcTemplate y EntityManagerFactory de mentira: alcanzan para que el bean se construya
         * (el EntityManager compartido se crea perezoso y nunca se usa acá).
         */
        private ApplicationContextRunner conJpaSimulado() {
            return contexto
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class));
        }
    }
}
