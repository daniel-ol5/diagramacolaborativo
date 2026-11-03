package com.modelcollab.vision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.ai.service.ModeloConRespaldo;
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
 * Adaptador contra la API de OpenAI multimodal (ej. gpt-4o-mini con vision), detrás de la misma
 * interfaz que el cliente simulado y el de Gemini.
 *
 * <p>Envía la imagen como data-URI en base64 usando el endpoint estándar de {@code /chat/completions}.
 */
public class OpenAiVisionModelClient implements VisionModelClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OpenAiVisionModelClient.class);

    private final AiProperties propiedades;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final ModeloConRespaldo conRespaldo;

    private static final Duration CONEXION = Duration.ofSeconds(5);

    public OpenAiVisionModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        this(propiedades, objectMapper, new ReintentoAnteSaturacion());
    }

    public OpenAiVisionModelClient(
            AiProperties propiedades, ObjectMapper objectMapper, ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
        this.conRespaldo = new ModeloConRespaldo(propiedades, reintento);

        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(CONEXION);
        // La lectura de imágenes tarda más que una consulta simple de texto: el timeout va al triple
        fabrica.setReadTimeout(Duration.ofMillis(propiedades.timeoutMs() * 3L));

        this.http = RestClient.builder()
                .baseUrl(propiedades.endpoint())
                .requestFactory(fabrica)
                .build();
    }

    @Override
    public String leer(byte[] imagen, String tipoMime) {
        return textoDe(conRespaldo.pedir("La lectura de fotos", modelo -> llamar(modelo, imagen, tipoMime)));
    }

    private String llamar(String modelo, byte[] imagen, String tipoMime) {
        String dataUri = "data:" + tipoMime + ";base64," + Base64.getEncoder().encodeToString(imagen);

        Map<String, Object> cuerpo = Map.of(
                "model", modelo,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", instrucciones()),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUri))))));

        byte[] crudo = http.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + propiedades.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(byte[].class);

        return crudo == null ? null : new String(crudo, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String descripcion() {
        return "OpenAI multimodal (" + propiedades.model() + ")";
    }

    @Override
    public boolean esSimulado() {
        return false;
    }

    private String textoDe(String respuesta) {
        try {
            JsonNode raiz = objectMapper.readTree(respuesta);
            JsonNode texto = raiz.path("choices").path(0)
                    .path("message").path("content");
            if (texto.isMissingNode() || !texto.isTextual()) {
                throw new RespuestaDeIaInvalidaException(
                        AiCommandResponse.CONTRATO_ROTO,
                        "El servicio de visión de OpenAI respondió sin contenido utilizable");
            }
            return texto.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO,
                    "El servicio de visión de OpenAI respondió algo que no es JSON", e);
        }
    }

    private String instrucciones() {
        return """
            Mirá esta foto o captura de un diagrama de clases UML (dibujado a mano, en pizarra o en software).

            Devolvé SÓLO un objeto JSON con esta forma exacta:
            {
              "classes": [
                {
                  "name": "NombreClase",
                  "attributes": [
                    {"name": "nombreAtributo", "type": "INTEGER|BIGINT|VARCHAR|TEXT|DECIMAL|BOOLEAN|DATE|DATETIME|UUID"}
                  ],
                  "methods": [
                    {"name": "nombreMetodo", "returnType": "void|tipo"}
                  ]
                }
              ],
              "relationships": [
                {
                  "from": "ClaseOrigen",
                  "to": "ClaseDestino",
                  "type": "ASSOCIATION|AGGREGATION|COMPOSITION|INHERITANCE|DEPENDENCY|MANY_TO_MANY",
                  "sourceMultiplicity": "0..1|1..1|0..*|1..*",
                  "targetMultiplicity": "0..1|1..1|0..*|1..*"
                }
              ]
            }

            Reglas de extracción:
            1. CLASES Y ATRIBUTOS:
               - Transcribí cada clase con su nombre exacto.
               - Atributos (segundo compartimiento del recuadro): nombre y tipo.
                 Mapeo de tipos:
                 Number / Int / Integer -> INTEGER
                 String / str / texto -> VARCHAR
                 Boolean / bool -> BOOLEAN
                 Float / Double / Decimal -> DECIMAL
                 Date / Fecha -> DATE
                 DateTime / Timestamp -> DATETIME
                 Si no se especifica o no se lee el tipo, poné VARCHAR.

            2. MÉTODOS U OPERACIONES (TERCER COMPARTIMIENTO DEL RECUADRO):
               - Si la clase tiene métodos (ej. "+ Vender(): void", "+ Abrir(): void", "+ Cerrar(): void", "+ Encender(): void", "+ FijarTemperatura(valor: Number): Void", "+ Subir(): void"):
                 Transcribí cada uno en "methods" con su nombre (sin los paréntesis) y su tipo de retorno (ej. "void", "Boolean", etc.).
               - Si una clase no tiene métodos dibujados, devolvé "methods": [].

            3. DIRECCIÓN EN COMPOSICIÓN Y AGREGACIÓN (EXTREMADAMENTE CRÍTICO):
               - En UML, el rombo (sea relleno/negro ◆ de COMPOSITION o hueco ◇ de AGGREGATION) indica el CONTENEDOR / TODO.
               - "from": SIEMPRE es la clase que TIENE EL ROMBO (el todo / contenedor).
               - "to": SIEMPRE es la clase del otro extremo SIN ROMBO (la parte / contenido).
               - "sourceMultiplicity": multiplicidad en el extremo de "from" (junto al rombo).
               - "targetMultiplicity": multiplicidad en el extremo de "to" (junto a la parte).
               - ¡NUNCA INVIERTAS ORIGEN Y DESTINO!
                 Ejemplo: Si 'Casa' tiene un rombo negro y la línea se conecta a 'Puerta', 'Ventana' y 'Calefactor':
                   {"from": "Casa", "to": "Puerta", "type": "COMPOSITION", "sourceMultiplicity": "1..1", "targetMultiplicity": "1..1"}
                   {"from": "Casa", "to": "Ventana", "type": "COMPOSITION", "sourceMultiplicity": "1..1", "targetMultiplicity": "1..1"}
                   {"from": "Casa", "to": "Calefactor", "type": "COMPOSITION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..1"}
                 Si 'Ventana' tiene un rombo negro hacia 'Persiana':
                   {"from": "Ventana", "to": "Persiana", "type": "COMPOSITION", "sourceMultiplicity": "1..1", "targetMultiplicity": "1..1"}

            4. DIRECCIÓN EN HERENCIA (INHERITANCE):
               - "from" es SIEMPRE la subclase (hijo).
               - "to" es SIEMPRE la superclase (padre, hacia donde apunta el triángulo hueco).

            5. MULTIPLICIDADES:
               - Transcribí las multiplicidades exactas escritas en los extremos:
                 Si dice "1" -> "1..1"
                 Si dice "0" -> "0..1"
                 Si dice "*" o "0..*" -> "0..*"
                 Si dice "1..*" -> "1..*"

            6. CLASE ASOCIACIÓN:
               - Si un recuadro cuelga de una línea con trazo punteado al punto medio, descomponelo en dos relaciones de tipo ASSOCIATION hacia esa clase intermedia.

            7. No inventes clases ni relaciones que no estén en la imagen. Si no se reconoce nada, listas vacías.
            """;
    }
}

