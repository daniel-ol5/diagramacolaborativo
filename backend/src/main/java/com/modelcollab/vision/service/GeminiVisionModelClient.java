package com.modelcollab.vision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.ai.service.ReintentoAnteSaturacion;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * El adaptador real contra Gemini 2.0 Flash multimodal (§15), detrás de la misma interfaz que el
 * simulado.
 *
 * <h2>ADVERTENCIA: este archivo nunca se ejecutó</h2>
 *
 * <p>Igual que su hermano de CU16: sin clave de API no hay ni una corrida contra el servicio. Lo
 * que está probado es todo lo que viene después —el parseo, la fusión, el circuito de revisión y
 * la aplicación— que no distingue de dónde salió el texto.
 *
 * <p>La imagen viaja en base64 dentro del cuerpo JSON, que es como lo pide la API. Eso multiplica
 * el tamaño por cuatro tercios, y es la razón de fondo por la que el límite de entrada está en
 * ocho megabytes y no en cuarenta.
 */
public class GeminiVisionModelClient implements VisionModelClient {

    private final AiProperties propiedades;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final ReintentoAnteSaturacion reintento;

    public GeminiVisionModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        this(propiedades, objectMapper, new ReintentoAnteSaturacion());
    }

    public GeminiVisionModelClient(
            AiProperties propiedades, ObjectMapper objectMapper, ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
        this.reintento = reintento;

        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofMillis(propiedades.timeoutMs()));
        // Leer una imagen tarda mas que contestar una frase, asi que el tiempo de lectura va al
        // triple. Con el mismo numero que CU16, una foto grande daria siempre tiempo agotado y
        // pareceria que el servicio esta caido.
        fabrica.setReadTimeout(Duration.ofMillis(propiedades.timeoutMs() * 3L));

        this.http = RestClient.builder()
                .baseUrl(propiedades.endpoint())
                .requestFactory(fabrica)
                .build();
    }

    @Override
    public String leer(byte[] imagen, String tipoMime) {
        Map<String, Object> cuerpo = Map.of(
                "contents", List.of(Map.of("parts", List.of(
                        Map.of("text", instrucciones()),
                        Map.of("inline_data", Map.of(
                                "mime_type", tipoMime,
                                "data", Base64.getEncoder().encodeToString(imagen)))))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "responseMimeType", "application/json"));

        // Mismo reintento que CU16, y por el mismo motivo: el 503 de saturación va y viene en
        // ráfagas. Acá todavía no se aplicó nada al diagrama —esto sólo mira la foto— así que
        // repetir la llamada no puede duplicar ninguna clase.
        String respuesta = reintento.ejecutar("La lectura de fotos", () -> http.post()
                .uri("/models/{modelo}:generateContent", propiedades.model())
                // En cabecera y no en la URL: una URL termina en los logs de acceso y en el
                // historial de cualquier proxy que haya en el medio.
                .header("x-goog-api-key", propiedades.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(String.class));

        return textoDe(respuesta);
    }

    @Override
    public String descripcion() {
        return "Gemini multimodal (" + propiedades.model() + ")";
    }

    @Override
    public boolean esSimulado() {
        return false;
    }

    private String textoDe(String respuesta) {
        try {
            JsonNode raiz = objectMapper.readTree(respuesta);
            JsonNode texto = raiz.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");
            if (texto.isMissingNode() || !texto.isTextual()) {
                throw new RespuestaDeIaInvalidaException(
                        AiCommandResponse.CONTRATO_ROTO,
                        "El servicio de visión respondió sin contenido utilizable");
            }
            return texto.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO,
                    "El servicio de visión respondió algo que no es JSON", e);
        }
    }

    /**
     * El prompt de extracción de grafo (RF-03.2).
     *
     * <p>La instrucción que más importa es la última: <b>que no complete lo que no ve</b>. Un
     * modelo al que se le pide "extraé el modelo de datos" tiende a devolver el diagrama que
     * <i>debería</i> haber para ese dominio, con sus clases canónicas y sus claves primarias, en
     * vez del que está dibujado en la cartulina. Eso es la misma alucinación que RF-02.6 rechaza
     * en CU16, sólo que entrando por otra puerta — y acá el guardarraíl que la frena es la
     * persona que revisa, así que conviene no darle trabajo de más.
     */
    private String instrucciones() {
        return """
            Mirá esta foto de un diagrama de clases dibujado a mano en una pizarra o en papel.

            Devolvé SÓLO un objeto JSON con esta forma exacta:
            {"classes":[{"name":"...","attributes":[{"name":"...","type":"..."}]}],
             "relationships":[{"from":"...","to":"...","type":"...",
                               "sourceMultiplicity":"...","targetMultiplicity":"..."}]}

            - type de atributo: INTEGER, BIGINT, VARCHAR, TEXT, DECIMAL, BOOLEAN, DATE, DATETIME
              o UUID. Si no se llega a leer, poné VARCHAR.
            - type de relación: ASSOCIATION, AGGREGATION, COMPOSITION, INHERITANCE, DEPENDENCY
              o MANY_TO_MANY.
            - multiplicidades: 0..1, 1..1, 0..* o 1..*
            - from y to son NOMBRES de clase, tal como están escritos en la pizarra.

            Reglas:
            1. Transcribí lo que está dibujado, no lo que debería estar. Si la pizarra tiene tres
               clases, devolvé tres — no agregues las que "faltan".
            2. No inventes atributos que no estén escritos. Una clase sin atributos legibles va
               con la lista vacía.
            3. No inventes claves primarias ni identificadores: si no está escrito, no está.
            4. Si una línea sale del borde de la foto y no se ve a dónde va, no la devuelvas.
            5. Si no se reconoce ninguna clase, devolvé las dos listas vacías.
            """;
    }
}
