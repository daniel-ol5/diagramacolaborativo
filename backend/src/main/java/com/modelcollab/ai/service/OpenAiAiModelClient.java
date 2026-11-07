package com.modelcollab.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Adaptador contra la API de OpenAI (ej. gpt-4o-mini, gpt-4o), detrás de la misma interfaz
 * que el cliente simulado y el de Gemini.
 *
 * <p>Utiliza el endpoint estándar {@code /chat/completions} con {@code response_format: { type: "json_object" }}
 * y temperatura 0 para garantizar resultados deterministas y estructurados en JSON.
 */
public class OpenAiAiModelClient implements AiModelClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OpenAiAiModelClient.class);

    private final AiProperties propiedades;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final ModeloConRespaldo conRespaldo;

    private static final Duration CONEXION = Duration.ofSeconds(5);

    public OpenAiAiModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        this(propiedades, objectMapper, new ReintentoAnteSaturacion());
    }

    public OpenAiAiModelClient(
            AiProperties propiedades, ObjectMapper objectMapper, ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
        this.conRespaldo = new ModeloConRespaldo(propiedades, reintento);

        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(CONEXION);
        fabrica.setReadTimeout(Duration.ofMillis(propiedades.timeoutMs()));

        this.http = RestClient.builder()
                .baseUrl(propiedades.endpoint())
                .requestFactory(fabrica)
                .build();
    }

    @Override
    public String responder(String comando, List<String> clasesDelDiagrama) {
        return textoDe(conRespaldo.pedir("El asistente", modelo -> llamar(modelo, comando, clasesDelDiagrama)));
    }

    private String llamar(String modelo, String comando, List<String> clasesDelDiagrama) {
        Map<String, Object> cuerpo = Map.of(
                "model", modelo,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", instrucciones(comando, clasesDelDiagrama))));

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
        return "OpenAI (" + propiedades.model() + ")";
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
                        "El servicio de OpenAI respondió sin contenido utilizable");
            }
            String contenido = texto.asText();
            if (contenido.contains("\"EDIT_MODEL|GENERATE_DOMAIN|UNSUPPORTED\"")) {
                contenido = contenido.replace(
                        "\"EDIT_MODEL|GENERATE_DOMAIN|UNSUPPORTED\"",
                        "\"UNSUPPORTED\"");
            }
            return contenido;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO,
                    "El servicio de OpenAI respondió algo que no es JSON", e);
        }
    }

    private String instrucciones(String comando, List<String> clases) {
        String existentes = clases.isEmpty()
                ? "(el diagrama está vacío)"
                : String.join(", ", clases);

        return """
            Sos un asistente de edición de diagramas de clases UML. Traducís UNA orden del \
            usuario a operaciones concretas sobre el diagrama que ya existe.

            Clases que existen ahora mismo: %s

            Respondé SÓLO con un objeto JSON con esta estructura exacta:
            {
              "intent": "EDIT_MODEL",
              "reply": "texto corto en castellano explicando lo que hiciste o por qué no se puede",
              "operations": [
                {
                  "op": "ADD_CLASS",
                  "target": "NombreClase",
                  "args": {}
                }
              ]
            }

            Valores válidos para "intent" (debes elegir exactamente UNO de estos tres):
            - "EDIT_MODEL": para aplicar operaciones sobre el diagrama (crear, modificar o eliminar).
            - "GENERATE_DOMAIN": si el usuario pide generar un dominio o sistema completo desde cero.
            - "UNSUPPORTED": si la orden no se entiende o no es realizable.

            Operaciones permitidas y sus args:
            - ADD_CLASS: target = nombre de la clase nueva. args: {} o {"isAbstract":true}
            - ADD_ATTRIBUTE: target = nombre de la clase existente. args: {"name":"...","type":"..."}
              type es uno de: INTEGER, BIGINT, VARCHAR, TEXT, DECIMAL, BOOLEAN, DATE, DATETIME, UUID
            - RENAME_CLASS: target = nombre actual de la clase. args: {"name":"nombre nuevo"}
            - MOVE_CLASS: target = nombre de la clase. args: {"direction":"LEFT|RIGHT|UP|DOWN"}
            - ADD_RELATIONSHIP: target = clase origen. args: {"to":"clase destino",
              "type":"ASSOCIATION|AGGREGATION|COMPOSITION|INHERITANCE|DEPENDENCY|MANY_TO_MANY",
              "sourceMultiplicity":"0..1|1..1|0..*|1..*","targetMultiplicity":"..."}
            - DELETE_CLASS: target = nombre de la clase a eliminar. args: {}
            - DELETE_ATTRIBUTE: target = nombre de la clase que contiene el atributo. args: {"name":"nombre del atributo a eliminar"}
            - DELETE_RELATIONSHIP: target = clase origen. args: {"to":"clase destino"} (o {"to":"huerfana"} si se pide eliminar una relación huérfana)

            Reglas que no se negocian:
            1. Referite a las clases por NOMBRE exacto, nunca por id.
            2. Como máximo 5 operaciones. Si hacen falta más, devolvé intent "UNSUPPORTED".
            3. Si te piden generar un sistema, un dominio o una aplicación entera, devolvé intent "GENERATE_DOMAIN" y operations: [].
            4. No inventes clases que no estén en la lista, salvo que la orden sea crearlas con ADD_CLASS.
            5. Si te piden eliminar o borrar una clase, atributo o relación existente (incluyendo relaciones huérfanas), genera la operación DELETE_CLASS, DELETE_ATTRIBUTE o DELETE_RELATIONSHIP correspondiente con intent "EDIT_MODEL". Si la orden combina varias acciones (hasta 5 operaciones), devuélvelas todas en orden dentro de "operations".
            6. Si no entendés la orden o no es realizable con las operaciones permitidas, devolvé intent "UNSUPPORTED" y operations: [].

            Orden del usuario: %s
            """.formatted(existentes, comando);
    }
}
