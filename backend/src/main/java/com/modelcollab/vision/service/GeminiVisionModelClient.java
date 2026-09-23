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
 * El adaptador real contra Gemini 2.0 Flash multimodal (§15), detrás de la misma interfaz que el
 * simulado.
 *
 * <h2>La regla 6 del prompt NO está verificada</h2>
 *
 * <p>La resolución de la clase asociación —una caja colgando de una línea punteada— está escrita
 * pero <b>nunca se probó contra una foto real</b>: el día que se agregó, la cuota del modelo
 * estaba agotada. Lo que sí está probado, y offline, es el otro extremo: que una clase con dos
 * relaciones hacia ella <b>sobrevive la fusión</b> y entra al modelo canónico
 * ({@code ClaseAsociacionTest}). O sea que el destino funciona; lo que falta es comprobar que el
 * modelo lea la pizarra así.
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GeminiVisionModelClient.class);

    private final AiProperties propiedades;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final ModeloConRespaldo conRespaldo;

    /**
     * Cuanto se espera para ABRIR la conexion, distinto del tiempo que se le da al modelo para
     * pensar. Ver el comentario del constructor.
     */
    private static final Duration CONEXION = Duration.ofSeconds(5);


    public GeminiVisionModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        this(propiedades, objectMapper, new ReintentoAnteSaturacion());
    }

    public GeminiVisionModelClient(
            AiProperties propiedades, ObjectMapper objectMapper, ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
        this.conRespaldo = new ModeloConRespaldo(propiedades, reintento);

        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        // CONECTAR y PENSAR son dos esperas distintas, y meterlas en el mismo numero sale caro.
        // Con 30 s para las dos, una red caida deja al usuario 30 segundos mirando la nada antes
        // del primer reintento — medido en la conexion de Daniel: un intento agoto 20 s sin
        // conectar y el siguiente salio en 8,8 s. Conectar tiene que fallar rapido; pensar puede
        // tardar, que para eso esta el tiempo de lectura.
        fabrica.setConnectTimeout(CONEXION);
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
        // Principal, respaldo y modelo retirado: la politica vive en una sola clase,
        // compartida con el otro cliente. Ver ModeloConRespaldo.
        return textoDe(conRespaldo.pedir("La lectura de fotos", modelo -> llamar(modelo, cuerpo)));
    }

    private String llamar(String modelo, Map<String, Object> cuerpo) {
        byte[] crudo = http.post()
                .uri("/models/{modelo}:generateContent", modelo)
                // En cabecera y no en la URL: una URL termina en los logs de acceso y en el
                // historial de cualquier proxy que haya en el medio.
                .header("x-goog-api-key", propiedades.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .accept(MediaType.ALL)
                .retrieve()
                // SE LEE COMO BYTES Y NO COMO String, y no es un detalle.
                //
                // Visto en el registro del 20/09: "Error while extracting response for type
                // [java.lang.String] and content type [application/octet-stream]". Gemini a
                // veces contesta con ese tipo de contenido en vez de application/json, y el
                // convertidor de String se niega a tocarlo — la respuesta llegaba entera y se
                // tiraba. El usuario veia "el asistente no esta disponible" cuando el asistente
                // habia contestado perfecto.
                //
                // El convertidor de byte[] acepta cualquier tipo de contenido. Lo que hay
                // adentro lo valida AiPlanParser, que es donde corresponde.
                .body(byte[].class);
        return crudo == null ? null : new String(crudo, java.nio.charset.StandardCharsets.UTF_8);
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
            6. CLASE ASOCIACIÓN (MUY IMPORTANTE):
               En UML, una clase asociación es un recuadro que se conecta mediante una LÍNEA PUNTEADA
               (o discontinua) al punto medio de una relación entre dos clases.
               NUNCA la devuelvas flotando, desconectada o huérfana en "classes" sin aristas.
               Debes descomponerla siempre de la siguiente manera:
               - En "classes": incluí la clase asociación con su nombre y sus atributos.
               - En "relationships": NO dejes la relación directa entre las dos clases principales.
                 En su lugar, generá DOS relaciones de tipo "ASSOCIATION", una desde cada clase hacia
                 la clase asociación.

               EJEMPLOS DE CLASE ASOCIACIÓN:
               1) Con nombre compuesto: si "Entrenador" y "Especialidad" están unidas por una línea,
                  y de esa línea cuelga con trazo punteado "EspecialidadEntrenador" (o "HorarioClase" entre "Clase" y "Horario"):
                  - Agregá las tres clases a "classes".
                  - Agregá a "relationships":
                    {"from": "Entrenador", "to": "EspecialidadEntrenador", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"},
                    {"from": "Especialidad", "to": "EspecialidadEntrenador", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
               2) Con nombre de acción o verbo: si entre "Equipo" y "Deposito" cuelga con trazo punteado
                  el recuadro "Guarda", o entre "Cliente" y "Evaluacion" cuelga "Evalua":
                  - NO agregues la relación directa {"from": "Equipo", "to": "Deposito"}.
                  - Agregá a "relationships":
                    {"from": "Equipo", "to": "Guarda", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"},
                    {"from": "Deposito", "to": "Guarda", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}
                  - Y para Evalua:
                    {"from": "Cliente", "to": "Evalua", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"},
                    {"from": "Evaluacion", "to": "Evalua", "type": "ASSOCIATION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}

            7. DIRECCIÓN EN COMPOSICIÓN Y AGREGACIÓN:
               - En COMPOSITION (rombo relleno/negro) y AGGREGATION (rombo hueco/blanco):
                 "from" es SIEMPRE la clase del TODO / CONTENEDOR (el extremo donde está dibujado el rombo).
                 "to" es SIEMPRE la clase de la PARTE / CONTENIDO (el extremo sin rombo).
                 sourceMultiplicity es la del lado del todo (ej. 1..1) y targetMultiplicity es la de la parte (ej. 0..* o 1..*).
                 Ejemplo: si "Clase" tiene el rombo negro y se conecta a "DetalleEquipo":
                 {"from": "Clase", "to": "DetalleEquipo", "type": "COMPOSITION", "sourceMultiplicity": "1..1", "targetMultiplicity": "0..*"}

            8. DIRECCIÓN EN HERENCIA (INHERITANCE):
               - "from" es SIEMPRE la subclase (hijo).
               - "to" es SIEMPRE la superclase (padre, hacia donde apunta el triángulo hueco).
            """;
    }
}
