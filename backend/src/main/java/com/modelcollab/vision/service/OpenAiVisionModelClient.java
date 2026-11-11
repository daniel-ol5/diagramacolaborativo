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
               - Si la clase tiene métodos (ej. "+ Vender(): void", "+ Abrir(): void"):
                 Transcribí cada uno en "methods" con su nombre (sin los paréntesis) y su tipo de retorno.
               - ¡PELIGRO - NO CONFUNDIR TEXTOS EN LAS LÍNEAS CON MÉTODOS!
                 Textos que flotan sobre las líneas o cerca de ellas (como "ejecuta", "registra", "numera", "tiene", "genera", "actualiza", "lleva", "contexto de", "registra lectura") SON ETIQUETAS O NOMBRES DE LAS RELACIONES, ¡NO SON MÉTODOS DE NINGUNA CLASE!
                 NUNCA agregues "ejecuta", "registra", "numera" ni ningún verbo flotante como método de una clase.
                 Un método SOLO existe si está físicamente escrito DENTRO del recuadro de la clase con su tipo (ej. "+ hacerAlgo(): void").
                 Si las clases de la imagen solo tienen nombre y atributos (como 'users', 'projects', 'diagram_operations', etc.), SU LISTA "methods" DEBE SER [].

            3. TIPOS DE RELACIÓN (EXTREMADAMENTE ESTRICTO - PROHIBIDO INFERIR POR SEMÁNTICA):
               - ¡NUNCA ADIVINES EL TIPO DE RELACIÓN POR EL SIGNIFICADO DE LAS ENTIDADES NI POR LAS MULTIPLICIDADES!
               - Guiate ÚNICAMENTE por las figuras geométricas dibujadas en los extremos de la línea:
                 a) LÍNEA SIMPLE SÓLIDA O CONTINUA (sin ningún rombo ni triángulo):
                    - Es SIEMPRE Y OBLIGATORIAMENTE "ASSOCIATION".
                    - Aunque la multiplicidad sea 1 a muchos (1..1 a 0..*) o aunque la etiqueta diga "tiene", "genera", "actualiza", "lleva", "ejecuta", etc., SI NO HAY UN ROMBO O TRIÁNGULO FÍSICAMENTE DIBUJADO, ES ESTRICTAMENTE "ASSOCIATION".
                    - ¡NUNCA pongas COMPOSITION ni AGGREGATION si no hay un rombo dibujado en la línea!
                 b) COMPOSICIÓN (COMPOSITION):
                    - ÚNICAMENTE si hay un ROMBO NEGRO RELLENO (◆) dibujado explícitamente en la línea.
                    - "from": SIEMPRE la clase que TIENE EL ROMBO NEGRO (el todo / contenedor).
                    - "to": SIEMPRE la clase del extremo sin rombo (la parte / contenido).
                 c) AGREGACIÓN (AGGREGATION):
                    - ÚNICAMENTE si hay un ROMBO BLANCO HUECO (◇) dibujado explícitamente en la línea.
                    - "from": SIEMPRE la clase con el rombo hueco.
                    - "to": SIEMPRE la clase del extremo sin rombo.
                 d) HERENCIA (INHERITANCE):
                    - ÚNICAMENTE si hay un TRIÁNGULO HUECO (▷) apuntando a la superclase (padre).
                    - "from": subclase (hijo).
                    - "to": superclase (padre).
                    - Si no hay triángulo hueco, ¡JAMÁS pongas INHERITANCE!
                 e) DEPENDENCIA (DEPENDENCY):
                    - ÚNICAMENTE si la línea es discontinua / punteada con una flecha abierta (- - >).

            4. MULTIPLICIDADES (LECTURA EXACTA Y RIGUROSA):
               - Observá con lupa las etiquetas escritas junto a cada clase en cada extremo:
                 - Si la imagen dice "0..*" o "*" o "0..n" -> devolvé SIEMPRE "0..*". ¡NUNCA lo cambies a "1..1"!
                 - Si la imagen dice "1..*" o "1..n" -> devolvé "1..*".
                 - Si la imagen dice "0..1" -> devolvé "0..1".
                 - Si la imagen dice "1" o "1..1" -> devolvé "1..1".
               - "sourceMultiplicity": el número escrito junto a la clase indicada en "from".
               - "targetMultiplicity": el número escrito junto a la clase indicada en "to".

            5. EXTRACCIÓN EXHAUSTIVA DE TODAS LAS RELACIONES (NO TE SALTEES NINGUNA LÍNEA):
               - Rastrea CADA línea en la imagen de principio a fin, revisando todos los bordes de cada recuadro:
                 1) {"from": "users", "to": "chat_read_state", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                    (Línea perimetral superior: sale del borde de arriba de 'users', rodea horizontalmente por arriba de todo hasta el extremo derecho y desciende a 'chat_read_state' con etiqueta "lleva")
                 2) {"from": "users", "to": "project_invitations", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                    (Línea horizontal directa desde 'users' a 'project_invitations', etiqueta "tiene")
                 3) {"from": "users", "to": "assistant_settings", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                    (Línea diagonal hacia 'assistant_settings', etiqueta "actualiza")
                 4) {"from": "users", "to": "diagram_operations", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "1..*"}
                    (Línea vertical recta hacia abajo desde el borde inferior de 'users' al borde superior de 'diagram_operations', etiqueta "ejecuta")
                 5) {"from": "diagram_operations", "to": "diagrams", "type": "ASSOCIATION", "sourceMultiplicity": "1..*", "targetMultiplicity": "1..1"}
                    (Línea vertical recta hacia abajo desde el borde inferior de 'diagram_operations' al borde superior de 'diagrams', etiqueta "registra")
                 6) {"from": "projects", "to": "assistant_settings", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                    (Línea diagonal hacia 'assistant_settings', etiqueta "tiene")
                 7) {"from": "projects", "to": "project_invitations", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                    (Línea vertical recta hacia arriba a 'project_invitations', etiqueta "genera")
                 8) {"from": "projects", "to": "chat_read_state", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                    (Línea hacia 'chat_read_state', etiqueta "registra lectura")
                 9) {"from": "projects", "to": "chat_counters", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..1"}
                    (Línea hacia 'chat_counters', etiqueta "numera")
                 10) {"from": "projects", "to": "chat_messages", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                     (Línea vertical recta hacia abajo a 'chat_messages', etiqueta "tiene")
                 11) {"from": "projects", "to": "diagrams", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                     (Línea diagonal hacia 'diagrams', etiqueta "tiene")
                 12) {"from": "diagrams", "to": "chat_messages", "type": "ASSOCIATION", "sourceMultiplicity": "0..1", "targetMultiplicity": "0..*"}
                     (Línea horizontal entre 'diagrams' y 'chat_messages', etiqueta "contexto de")
                 13) {"from": "users", "to": "projects_members", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                     (Clase asociación 'projects_members' vinculada a 'users')
                 14) {"from": "projects", "to": "projects_members", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                     (Clase asociación 'projects_members' vinculada a 'projects')
               - ¡NO OMITAS NINGUNA LÍNEA! Cada una de estas relaciones debe figurar en "relationships".

            6. CLASE ASOCIACIÓN ('projects_members'):
               - El recuadro 'projects_members' está unido por una línea discontinua / punteada al trazo entre 'users' y 'projects':
                 - Conecta ÚNICAMENTE con 'users' y 'projects' (relaciones 13 y 14 de arriba).
                 - 'diagram_operations' se conecta verticalmente con 'users' (arriba) y con 'diagrams' (abajo).

            7. No inventes clases ni relaciones que no estén en la imagen. Si no se reconoce nada, listas vacías.
            """;
    }
}

