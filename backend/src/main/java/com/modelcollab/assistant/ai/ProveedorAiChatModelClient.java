package com.modelcollab.assistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.ai.service.ModeloConRespaldo;
import com.modelcollab.ai.service.ReintentoAnteSaturacion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link AiChatModelClient} sobre el proveedor ya configurado para CU16 ({@code AiProperties}):
 * OpenAI ({@code /chat/completions}) o Gemini ({@code /models/{m}:generateContent}).
 *
 * <ul>
 *   <li>Mismos envoltorios que CU16, reutilizados tal cual: {@link ReintentoAnteSaturacion}
 *       (red y 503) dentro de {@link ModeloConRespaldo} (404, 429 y saturación pasan al modelo
 *       de respaldo).
 *   <li>{@code RestClient} con 5 s de conexión y {@code timeoutMs} de lectura; la URL base es
 *       {@code AiProperties.endpoint()}.
 *   <li>Texto libre: no se fuerza JSON. Temperatura y tokens salen del {@link ChatRequest}
 *       (0.4 y 400 por defecto).
 *   <li>Cualquier fallo sale como {@link AiUnavailableException}; el 429 del proveedor con su
 *       propio motivo.
 * </ul>
 *
 * <p><b>Modelo local:</b> un servidor compatible con la API de OpenAI (Ollama, LM Studio,
 * vLLM...) se usaría con {@code AI_PROVIDER=openai} y {@code OPENAI_ENDPOINT} apuntando a su URL
 * base (p. ej. {@code http://localhost:11434/v1}). No está implementado ni probado: haría falta
 * al menos una clave no vacía (para no caer en el simulado) y revisar que acepte {@code
 * max_tokens}.
 */
public class ProveedorAiChatModelClient implements AiChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(ProveedorAiChatModelClient.class);

    private static final Duration CONEXION = Duration.ofSeconds(5);
    private static final String QUE_ES = "La IA del chat";

    private final AiProperties propiedades;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final ModeloConRespaldo conRespaldo;

    public ProveedorAiChatModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        this(propiedades, objectMapper,
                RestClient.builder().requestFactory(fabrica(propiedades)),
                new ReintentoAnteSaturacion());
    }

    /** Para tests: el builder puede venir enlazado a un {@code MockRestServiceServer}. */
    ProveedorAiChatModelClient(
            AiProperties propiedades,
            ObjectMapper objectMapper,
            RestClient.Builder builder,
            ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
        this.conRespaldo = new ModeloConRespaldo(propiedades, reintento);
        this.http = builder.baseUrl(propiedades.endpoint()).build();
    }

    private static SimpleClientHttpRequestFactory fabrica(AiProperties propiedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(CONEXION);
        fabrica.setReadTimeout(Duration.ofMillis(propiedades.timeoutMs()));
        return fabrica;
    }

    @Override
    public ChatCompletion chat(ChatRequest request) {
        AtomicReference<String> modeloUsado = new AtomicReference<>(propiedades.model());
        String crudo;
        try {
            crudo = conRespaldo.pedir(QUE_ES, modelo -> {
                modeloUsado.set(modelo);
                return propiedades.esOpenAi()
                        ? llamarOpenAi(modelo, request)
                        : llamarGemini(modelo, request);
            });
        } catch (RuntimeException e) {
            throw traducir(e);
        }
        return new ChatCompletion(textoDe(crudo), modeloUsado.get());
    }

    @Override
    public String descripcion() {
        return (propiedades.esOpenAi() ? "OpenAI" : "Gemini") + " chat (" + propiedades.model() + ")";
    }

    @Override
    public boolean esSimulado() {
        return false;
    }

    // ------------------------------------------------------------- OpenAI

    private String llamarOpenAi(String modelo, ChatRequest request) {
        List<Map<String, Object>> mensajes = new ArrayList<>();
        if (!request.system().isBlank()) {
            mensajes.add(Map.of("role", "system", "content", request.system()));
        }
        for (ChatTurn turno : request.turns()) {
            mensajes.add(Map.of(
                    "role", turno.role() == ChatTurn.Role.ASSISTANT ? "assistant" : "user",
                    "content", turno.content()));
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("model", modelo);
        cuerpo.put("temperature", request.temperature());
        cuerpo.put("max_tokens", request.maxTokens());
        cuerpo.put("messages", mensajes);
        return bytes(http.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + propiedades.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(byte[].class));
    }

    // ------------------------------------------------------------- Gemini

    private String llamarGemini(String modelo, ChatRequest request) {
        List<Map<String, Object>> contenidos = new ArrayList<>();
        for (ChatTurn turno : unirConsecutivos(request.turns())) {
            contenidos.add(Map.of(
                    "role", turno.role() == ChatTurn.Role.ASSISTANT ? "model" : "user",
                    "parts", List.of(Map.of("text", turno.content()))));
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        if (!request.system().isBlank()) {
            cuerpo.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.system()))));
        }
        cuerpo.put("contents", contenidos);
        cuerpo.put("generationConfig", Map.of(
                "temperature", request.temperature(),
                "maxOutputTokens", request.maxTokens()));
        return bytes(http.post()
                .uri("/models/{modelo}:generateContent", modelo)
                .header("x-goog-api-key", propiedades.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .body(cuerpo)
                .retrieve()
                .body(byte[].class));
    }

    /**
     * Gemini pide que los turnos alternen entre user y model, y en un chat grupal lo normal es
     * que hablen varias personas seguidas. Los turnos consecutivos del mismo rol se unen en uno,
     * separados por una línea en blanco; cada línea conserva su etiqueta "[Nombre]: ". OpenAI no
     * lo necesita y recibe los turnos tal cual.
     */
    static List<ChatTurn> unirConsecutivos(List<ChatTurn> turnos) {
        List<ChatTurn> unidos = new ArrayList<>();
        for (ChatTurn turno : turnos) {
            int ultimo = unidos.size() - 1;
            if (ultimo >= 0 && unidos.get(ultimo).role() == turno.role()) {
                ChatTurn previo = unidos.get(ultimo);
                unidos.set(ultimo, new ChatTurn(previo.role(), previo.content() + "\n\n" + turno.content()));
            } else {
                unidos.add(turno);
            }
        }
        return unidos;
    }

    // ------------------------------------------------------- respuesta y errores

    private static String bytes(byte[] crudo) {
        return crudo == null ? null : new String(crudo, StandardCharsets.UTF_8);
    }

    private String textoDe(String crudo) {
        JsonNode texto;
        try {
            JsonNode raiz = objectMapper.readTree(crudo == null ? "" : crudo);
            texto = propiedades.esOpenAi()
                    ? raiz.path("choices").path(0).path("message").path("content")
                    : raiz.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        } catch (Exception e) {
            log.warn("{} respondió algo que no es JSON: {}", descripcion(), e.toString());
            throw new AiUnavailableException(AiUnavailableException.Reason.INVALID_RESPONSE,
                    "La IA respondió algo que no se puede leer", e);
        }
        if (!texto.isTextual() || texto.asText().isBlank()) {
            log.warn("{} respondió sin texto utilizable", descripcion());
            throw new AiUnavailableException(AiUnavailableException.Reason.INVALID_RESPONSE,
                    "La IA respondió sin texto");
        }
        return texto.asText();
    }

    /**
     * La única traducción de fallos, como {@code AiCommandService.consultar} en CU16. El log
     * lleva la descripción del cliente y el error; de un error HTTP sólo el status, porque el
     * cuerpo de la respuesta del proveedor puede citar parte de la clave (OpenAI lo hace en el
     * 401). Nunca la clave ni el cuerpo de la petición.
     */
    private AiUnavailableException traducir(RuntimeException e) {
        if (e instanceof AiUnavailableException propia) {
            return propia;
        }
        boolean cuota = e instanceof HttpClientErrorException c && c.getStatusCode().value() == 429;
        String detalle = e instanceof RestClientResponseException r
                ? e.getClass().getSimpleName() + " " + r.getStatusCode().value()
                : e.toString();
        log.warn("{} no respondió [{}]: {}", descripcion(),
                cuota ? "CUOTA DEL PROVEEDOR AGOTADA (429)" : "no disponible", detalle);
        return cuota
                ? new AiUnavailableException(AiUnavailableException.Reason.PROVIDER_QUOTA,
                        "Se alcanzó el límite de uso del proveedor de IA. Hay que esperar unos minutos.", e)
                : new AiUnavailableException(AiUnavailableException.Reason.UNAVAILABLE,
                        "La IA no está disponible ahora.", e);
    }
}
