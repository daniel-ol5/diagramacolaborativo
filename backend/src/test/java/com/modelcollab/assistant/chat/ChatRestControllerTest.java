package com.modelcollab.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.assistant.core.ChatErrorCode;
import com.modelcollab.assistant.core.ChatMessage;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.assistant.core.ChatRejectedException;
import com.modelcollab.config.JacksonConfig;
import com.modelcollab.project.model.ProjectRole;
import com.modelcollab.project.service.ProjectService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * CU19, etapa 3: REST del chat. MockMvc standalone con el ObjectMapper canónico, servicios
 * mockeados y sin base de datos ni contexto de Spring.
 */
@ExtendWith(MockitoExtension.class)
class ChatRestControllerTest {

    private static final ObjectMapper MAPPER = JacksonConfig.canonicalObjectMapper();
    private static final Instant CREADO = Instant.parse("2026-11-05T14:03:22.123Z");

    @Mock private ChatService chatService;
    @Mock private ProjectService projectService;

    private MockMvc mvc;

    private final UUID projectId = UUID.randomUUID();
    private final UUID anaId = UUID.randomUUID();
    private final Principal ana = new UsernamePasswordAuthenticationToken(anaId.toString(), null, List.of());
    private final String url = "/api/v1/projects/" + projectId + "/chat";

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatRestController(chatService, projectService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .build();
    }

    private ChatMessage mensaje(long seq, String clientMessageId) {
        return new ChatMessage("m-" + seq, projectId.toString(), null, seq, ChatAuthorType.USER,
                anaId.toString(), "Ana Pérez", "#123456", null, null, null, clientMessageId,
                ChatMessageKind.CHAT, "hola " + seq, CREADO, null);
    }

    // ------------------------------------------------------------------ POST

    @Test
    @DisplayName("POST nuevo: 201 con el mensaje guardado")
    void post201() throws Exception {
        when(chatService.postFromUser(eq(projectId), any(), any()))
                .thenReturn(new ChatPostResult(mensaje(7, "c-1"), false));

        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("m-7"))
                .andExpect(jsonPath("$.seq").value(7))
                .andExpect(jsonPath("$.clientMessageId").value("c-1"));
        verify(projectService).exigirMembresia(projectId, anaId);
    }

    @Test
    @DisplayName("POST duplicado del mismo usuario: 200 con el original")
    void post200Duplicado() throws Exception {
        when(chatService.postFromUser(eq(projectId), any(), any()))
                .thenReturn(new ChatPostResult(mensaje(3, "c-1"), true));

        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"otra vez\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("m-3"))
                .andExpect(jsonPath("$.text").value("hola 3"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ChatErrorCode.class)
    @DisplayName("Rechazo del servicio: su status y {code, message}")
    void rechazos(ChatErrorCode code) throws Exception {
        when(chatService.postFromUser(eq(projectId), any(), any()))
                .thenThrow(new ChatRejectedException(code, "motivo"));

        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}"))
                .andExpect(status().is(code.status()))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value("motivo"));
    }

    @Test
    @DisplayName("No miembro: el mismo 403 de la API, sin code, y el servicio ni se llama")
    void noMiembro403() throws Exception {
        when(projectService.exigirMembresia(projectId, anaId))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));

        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("\"code\""))));
        mvc.perform(get(url).principal(ana))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("\"code\""))));
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Cuerpo con authorId/authorName: 400 INVALID_PAYLOAD sin llamar al servicio")
    void cuerpoConAutor() throws Exception {
        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\","
                                + "\"authorId\":\"" + UUID.randomUUID() + "\",\"authorName\":\"Impostor\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"))
                .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Cuerpo ilegible: 400 INVALID_PAYLOAD")
    void cuerpoIlegible() throws Exception {
        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
    }

    @Test
    @DisplayName("El autor sale del Principal: es el que recibe el servicio")
    void autorDelPrincipal() throws Exception {
        when(chatService.postFromUser(eq(projectId), any(), any()))
                .thenReturn(new ChatPostResult(mensaje(1, "c-1"), false));

        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<Principal> quien = ArgumentCaptor.forClass(Principal.class);
        ArgumentCaptor<ChatPostRequest> cuerpo = ArgumentCaptor.forClass(ChatPostRequest.class);
        verify(chatService).postFromUser(eq(projectId), quien.capture(), cuerpo.capture());
        assertThat(quien.getValue().getName()).isEqualTo(anaId.toString());
        assertThat(cuerpo.getValue()).isEqualTo(new ChatPostRequest("c-1", "hola", null, null));
    }

    @Test
    @DisplayName("projectId que no es UUID: 400 INVALID_PAYLOAD")
    void projectIdInvalido() throws Exception {
        mvc.perform(get("/api/v1/projects/no-uuid/chat").principal(ana))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        mvc.perform(post("/api/v1/projects/no-uuid/chat").principal(ana)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        verifyNoInteractions(chatService, projectService);
    }

    // ------------------------------------------------------------------- GET

    @Test
    @DisplayName("GET: traduce los parámetros y devuelve {messages, hasMore} en el orden del servicio")
    void getPagina() throws Exception {
        when(chatService.history(eq(projectId), any(), eq(10L), eq(null), eq(2), eq(null), eq(null), eq(null)))
                .thenReturn(new ChatHistoryPage(List.of(mensaje(8, "a"), mensaje(9, "b")), true));

        mvc.perform(get(url).principal(ana).param("beforeSeq", "10").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].seq").value(8))
                .andExpect(jsonPath("$.messages[1].seq").value(9))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("GET sin parámetros: todos llegan nulos al servicio (él aplica el 50 por defecto)")
    void getSinParametros() throws Exception {
        when(chatService.history(eq(projectId), any(), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(new ChatHistoryPage(List.of(), false));

        mvc.perform(get(url).principal(ana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("GET con afterSeq: llega como afterSeq")
    void getAfter() throws Exception {
        when(chatService.history(eq(projectId), any(), eq(null), eq(3L), eq(2), eq(null), eq(null), eq(null)))
                .thenReturn(new ChatHistoryPage(List.of(mensaje(4, "a"), mensaje(5, "b")), true));

        mvc.perform(get(url).principal(ana).param("afterSeq", "3").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].seq").value(4))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("beforeSeq, afterSeq o limit no numéricos: 400 INVALID_PAYLOAD con {code, message}")
    void parametrosNoNumericos() throws Exception {
        for (String param : List.of("beforeSeq", "afterSeq", "limit")) {
            mvc.perform(get(url).principal(ana).param(param, "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"))
                    .andExpect(jsonPath("$.message").value(containsString(param)));
        }
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("beforeSeq y afterSeq a la vez: el rechazo del servicio sale como 400 INVALID_PAYLOAD")
    void ambosCursores() throws Exception {
        when(chatService.history(eq(projectId), any(), eq(5L), eq(2L), eq(null), eq(null), eq(null), eq(null)))
                .thenThrow(new ChatRejectedException(ChatErrorCode.INVALID_PAYLOAD, "Usá beforeSeq o afterSeq, no los dos"));

        mvc.perform(get(url).principal(ana).param("beforeSeq", "5").param("afterSeq", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
    }

    @Test
    @DisplayName("VIEWER lee y escribe: exigirMembresia le devuelve VIEWER y nada lo frena")
    void viewerLeeYEscribe() throws Exception {
        when(projectService.exigirMembresia(projectId, anaId)).thenReturn(ProjectRole.VIEWER);
        when(chatService.postFromUser(eq(projectId), any(), any()))
                .thenReturn(new ChatPostResult(mensaje(1, "c-1"), false));
        when(chatService.history(eq(projectId), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ChatHistoryPage(List.of(mensaje(1, "c-1")), false));
        when(chatService.readState(eq(projectId), any())).thenReturn(new ChatReadState(0, 1, 1));
        when(chatService.markRead(eq(projectId), any(), eq(1L))).thenReturn(new ChatReadState(1, 0, 1));

        mvc.perform(get(url + "/read").principal(ana)).andExpect(status().isOk());
        mvc.perform(put(url + "/read").principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upToSeq\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadSeq").value(1));
        mvc.perform(post(url).principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"c-1\",\"text\":\"hola\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(url).principal(ana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].seq").value(1));
    }

    // ------------------------------------------------------- serialización

    @Test
    @DisplayName("createdAt sale en ISO-8601 UTC (texto) y los nulos se omiten (NON_NULL)")
    void serializacion() throws Exception {
        when(chatService.history(eq(projectId), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ChatHistoryPage(List.of(mensaje(1, "c-1")), false));

        String json = mvc.perform(get(url).principal(ana))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode m = MAPPER.readTree(json).get("messages").get(0);
        assertThat(m.get("createdAt").isTextual()).isTrue();
        assertThat(m.get("createdAt").asText()).isEqualTo("2026-11-05T14:03:22.123Z");
        for (String opcional : List.of("diagramId", "replyToId", "addressedToId", "addressedToName", "meta")) {
            assertThat(m.has(opcional)).as("%s nulo se omite", opcional).isFalse();
        }
        for (String presente : List.of("id", "projectId", "seq", "authorType", "authorId", "authorName",
                "authorColor", "clientMessageId", "kind", "text", "createdAt")) {
            assertThat(m.has(presente)).as("%s presente", presente).isTrue();
        }

        // Directo con el mismo mapper, sin MockMvc de por medio: mismo resultado.
        JsonNode directo = MAPPER.valueToTree(mensaje(2, "c-2"));
        assertThat(directo.get("createdAt").asText()).isEqualTo("2026-11-05T14:03:22.123Z");
        assertThat(directo.has("diagramId")).isFalse();
    }

    // ------------------------------------------------------- CU23: filtros

    @Test
    @DisplayName("GET con q, authorType y kind: llegan tal cual al servicio, junto con los cursores")
    void getConFiltros() throws Exception {
        when(chatService.history(eq(projectId), any(), eq(20L), eq(null), eq(5), eq(" atrib "), eq("ai"), eq("SUMMARY")))
                .thenReturn(new ChatHistoryPage(List.of(mensaje(12, "a")), true));

        mvc.perform(get(url).principal(ana).param("beforeSeq", "20").param("limit", "5")
                        .param("q", " atrib ").param("authorType", "ai").param("kind", "SUMMARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].seq").value(12))
                .andExpect(jsonPath("$.hasMore").value(true));
        verify(projectService).exigirMembresia(projectId, anaId);
    }

    @Test
    @DisplayName("Filtro inválido: el rechazo del servicio sale como 400 {code: INVALID_PAYLOAD, message}")
    void getFiltroInvalido() throws Exception {
        when(chatService.history(eq(projectId), any(), any(), any(), any(), any(), eq("ROBOT"), any()))
                .thenThrow(new ChatRejectedException(ChatErrorCode.INVALID_PAYLOAD, "Valor inválido para authorType"));

        mvc.perform(get(url).principal(ana).param("authorType", "ROBOT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"))
                .andExpect(jsonPath("$.message").value("Valor inválido para authorType"));
    }

    // -------------------------------------------------- CU23: estado de lectura

    @Test
    @DisplayName("GET /read: 200 con {lastReadSeq, unreadCount, lastSeq}")
    void getRead() throws Exception {
        when(chatService.readState(eq(projectId), any())).thenReturn(new ChatReadState(4, 3, 9));

        mvc.perform(get(url + "/read").principal(ana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadSeq").value(4))
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.lastSeq").value(9));
        verify(projectService).exigirMembresia(projectId, anaId);
        ArgumentCaptor<Principal> quien = ArgumentCaptor.forClass(Principal.class);
        verify(chatService).readState(eq(projectId), quien.capture());
        assertThat(quien.getValue().getName()).isEqualTo(anaId.toString());
    }

    @Test
    @DisplayName("GET /read con ceros: los tres campos salen (son números, no se omiten)")
    void getReadCeros() throws Exception {
        when(chatService.readState(eq(projectId), any())).thenReturn(new ChatReadState(0, 0, 0));

        mvc.perform(get(url + "/read").principal(ana))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadSeq").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.lastSeq").value(0));
    }

    @Test
    @DisplayName("PUT /read {upToSeq}: 200 con el estado que devuelve el servicio")
    void putRead() throws Exception {
        when(chatService.markRead(eq(projectId), any(), eq(7L))).thenReturn(new ChatReadState(7, 2, 9));

        mvc.perform(put(url + "/read").principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upToSeq\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReadSeq").value(7))
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.lastSeq").value(9));
        verify(projectService).exigirMembresia(projectId, anaId);
    }

    @Test
    @DisplayName("PUT /read sin upToSeq o negativo: el servicio lo rechaza y sale 400 INVALID_PAYLOAD")
    void putReadUpToSeqInvalido() throws Exception {
        when(chatService.markRead(eq(projectId), any(), eq(null)))
                .thenThrow(new ChatRejectedException(ChatErrorCode.INVALID_PAYLOAD, "upToSeq es obligatorio"));
        when(chatService.markRead(eq(projectId), any(), eq(-1L)))
                .thenThrow(new ChatRejectedException(ChatErrorCode.INVALID_PAYLOAD, "no puede ser negativo"));

        for (String cuerpo : List.of("{}", "{\"upToSeq\":null}", "{\"upToSeq\":-1}")) {
            mvc.perform(put(url + "/read").principal(ana).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        }
    }

    @Test
    @DisplayName("PUT /read sin cuerpo, ilegible, no numérico o con campos desconocidos: 400 sin llamar al servicio")
    void putReadCuerpoInvalido() throws Exception {
        mvc.perform(put(url + "/read").principal(ana).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        for (String cuerpo : List.of("{no es json", "{\"upToSeq\":\"abc\"}", "{\"upToSeq\":1,\"userId\":\"x\"}")) {
            mvc.perform(put(url + "/read").principal(ana).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        }
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("/read de quien no es miembro: el 403 de la API, sin code, y el servicio ni se llama")
    void readNoMiembro() throws Exception {
        when(projectService.exigirMembresia(projectId, anaId))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));

        mvc.perform(get(url + "/read").principal(ana))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("\"code\""))));
        mvc.perform(put(url + "/read").principal(ana).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upToSeq\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("\"code\""))));
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("/read con projectId que no es UUID: 400 INVALID_PAYLOAD")
    void readProjectIdInvalido() throws Exception {
        mvc.perform(get("/api/v1/projects/no-uuid/chat/read").principal(ana))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYLOAD"));
        verifyNoInteractions(chatService, projectService);
    }
}
