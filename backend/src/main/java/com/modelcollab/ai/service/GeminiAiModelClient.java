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
 * El adaptador real contra Gemini 2.0 Flash (§15), detrás de la misma interfaz que el simulado.
 *
 * <h2>ADVERTENCIA: este archivo nunca se ejecutó</h2>
 *
 * <p>Al escribirlo, Daniel todavía no había conseguido la clave de API, así que <b>no hay ni una
 * sola corrida de esta clase contra el servicio</b>. Está escrita, compila y no está probada, y
 * decirlo es parte de entregarla: el resto del sistema tiene detrás una verificación de punta a
 * punta y esto no, así que no merece la misma confianza.
 *
 * <p>Lo que sí está probado es todo lo que viene después: el guardarraíl, el contrato de salida y
 * el mapeo a operaciones no distinguen de dónde salió el texto. Cambiar de cliente es cambiar
 * qué bean se construye, y esa costura sí tiene tests.
 *
 * <h2>Dos decisiones que sí se pueden justificar sin ejecutarlo</h2>
 *
 * <p><b>La clave va en una cabecera, no en la URL.</b> La API la acepta de las dos formas y el
 * ejemplo oficial usa {@code ?key=...}, pero una URL termina en los logs de acceso, en el
 * historial de cualquier proxy y en los mensajes de error de las librerías HTTP. Una cabecera no.
 *
 * <p><b>Temperatura 0 y salida JSON declarada.</b> No es para que el modelo "acierte más": es
 * para que dos comandos iguales den el mismo plan. Un asistente que hace algo distinto cada vez
 * que se le dicta lo mismo es imposible de demostrar y peor de depurar.
 */
public class GeminiAiModelClient implements AiModelClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GeminiAiModelClient.class);

    private final AiProperties propiedades;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final ReintentoAnteSaturacion reintento;

    /**
     * Cuanto se espera para ABRIR la conexion, distinto del tiempo que se le da al modelo para
     * pensar. Ver el comentario del constructor.
     */
    private static final Duration CONEXION = Duration.ofSeconds(5);


    public GeminiAiModelClient(AiProperties propiedades, ObjectMapper objectMapper) {
        this(propiedades, objectMapper, new ReintentoAnteSaturacion());
    }

    public GeminiAiModelClient(
            AiProperties propiedades, ObjectMapper objectMapper, ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.objectMapper = objectMapper;
        this.reintento = reintento;

        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        // Con timeout, siempre. Sin él, una llamada que no vuelve deja colgado el hilo que
        // atiende la petición y, con varios usuarios dictando, se lleva puesto el servidor
        // entero por un servicio de terceros que no responde.
        // CONECTAR y PENSAR son dos esperas distintas, y meterlas en el mismo numero sale caro.
        // Con 30 s para las dos, una red caida deja al usuario 30 segundos mirando la nada antes
        // del primer reintento — medido en la conexion de Daniel: un intento agoto 20 s sin
        // conectar y el siguiente salio en 8,8 s. Conectar tiene que fallar rapido; pensar puede
        // tardar, que para eso esta el tiempo de lectura.
        fabrica.setConnectTimeout(CONEXION);
        fabrica.setReadTimeout(Duration.ofMillis(propiedades.timeoutMs()));

        this.http = RestClient.builder()
                .baseUrl(propiedades.endpoint())
                .requestFactory(fabrica)
                .build();
    }

    @Override
    public String responder(String comando, List<String> clasesDelDiagrama) {
        Map<String, Object> cuerpo = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", instrucciones(comando, clasesDelDiagrama))))),
                "generationConfig", Map.of(
                        "temperature", 0,
                        "responseMimeType", "application/json"));

        // El reintento envuelve SOLO la llamada: acá todavía no se aplicó nada al diagrama, así
        // que repetirla no puede duplicar ninguna operación. Ver ReintentoAnteSaturacion.
        try {
            return textoDe(reintento.ejecutar(
                    "El asistente", () -> llamar(propiedades.model(), cuerpo)));
        } catch (RuntimeException e) {
            if (!ReintentoAnteSaturacion.esSaturacion(e)) {
                throw e;
            }
            // CAMBIAR DE MODELO ES LO ÚNICO QUE RECUPERA CUANDO REINTENTAR NO ALCANZA.
            //
            // La saturación y la cuota son POR MODELO. Medido el 20/09 con la misma clave y en
            // el mismo minuto: el principal devolvió 429 cuatro de cuatro veces mientras el de
            // respaldo devolvía 200 cuatro de cuatro. Insistir contra un modelo agotado falla
            // igual por más que se espere; el otro contesta al primer intento.
            //
            // Va UNA sola vez, sin escalera: si el de respaldo está sano contesta enseguida, y
            // si no lo está, lo que corresponde es decir la verdad y no hacer esperar más.
            log.warn("{} no respondió; se recurre al modelo de respaldo ({})",
                    propiedades.model(), propiedades.modeloDeRespaldo());
            return textoDe(llamar(propiedades.modeloDeRespaldo(), cuerpo));
        }
    }

    private String llamar(String modelo, Map<String, Object> cuerpo) {
        return http.post()
                .uri("/models/{modelo}:generateContent", modelo)
                .header("x-goog-api-key", propiedades.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(String.class);
    }

    @Override
    public String descripcion() {
        return "Gemini (" + propiedades.model() + ")";
    }

    @Override
    public boolean esSimulado() {
        return false;
    }

    /**
     * Saca el texto de la respuesta de la API.
     *
     * <p>Es la envoltura de Gemini, no el plan: {@code candidates[0].content.parts[0].text}. Lo
     * que hay adentro no se mira acá — de validarlo se encarga {@link AiPlanParser}, que es el
     * único lugar donde se decide si una respuesta cumple el contrato.
     */
    private String textoDe(String respuesta) {
        try {
            JsonNode raiz = objectMapper.readTree(respuesta);
            JsonNode texto = raiz.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");
            if (texto.isMissingNode() || !texto.isTextual()) {
                throw new RespuestaDeIaInvalidaException(
                        AiCommandResponse.CONTRATO_ROTO,
                        "El servicio de IA respondió sin contenido utilizable");
            }
            return texto.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO,
                    "El servicio de IA respondió algo que no es JSON", e);
        }
    }

    /**
     * El prompt.
     *
     * <p>Le dice al modelo qué puede pedir y cómo, pero <b>nada de esto es una garantía</b>: las
     * mismas reglas están implementadas del lado del servidor, y ahí sí lo son. Esto es la
     * cortesía de explicarle el contrato a quien tiene que cumplirlo; el que lo hace cumplir es
     * {@link AiPlanParser}.
     */
    private String instrucciones(String comando, List<String> clases) {
        String existentes = clases.isEmpty()
                ? "(el diagrama está vacío)"
                : String.join(", ", clases);

        return """
            Sos un asistente de edición de diagramas de clases UML. Traducís UNA orden del \
            usuario a operaciones concretas sobre el diagrama que ya existe.

            Clases que existen ahora mismo: %s

            Respondé SÓLO con un objeto JSON de esta forma exacta:
            {"intent":"EDIT_MODEL|GENERATE_DOMAIN|UNSUPPORTED","reply":"texto corto en \
            castellano","operations":[{"op":"...","target":"...","args":{...}}]}

            Operaciones permitidas y sus args:
            - ADD_CLASS: target = nombre de la clase nueva. args: {} o {"isAbstract":true}
            - ADD_ATTRIBUTE: target = nombre de la clase. args: {"name":"...","type":"..."}
              type es uno de INTEGER, BIGINT, VARCHAR, TEXT, DECIMAL, BOOLEAN, DATE, DATETIME, UUID
            - RENAME_CLASS: target = clase actual. args: {"name":"nombre nuevo"}
            - MOVE_CLASS: target = clase. args: {"direction":"LEFT|RIGHT|UP|DOWN"}
            - ADD_RELATIONSHIP: target = clase origen. args: {"to":"clase destino",
              "type":"ASSOCIATION|AGGREGATION|COMPOSITION|INHERITANCE|DEPENDENCY|MANY_TO_MANY",
              "sourceMultiplicity":"0..1|1..1|0..*|1..*","targetMultiplicity":"..."}

            Reglas que no se negocian:
            1. Referite a las clases por NOMBRE, nunca por identificador.
            2. Como máximo 5 operaciones. Si hacen falta más, devolvé intent UNSUPPORTED.
            3. Si te piden generar un sistema, un dominio o una aplicación entera, devolvé \
            intent GENERATE_DOMAIN y ninguna operación.
            4. No inventes clases que no estén en la lista, salvo que la orden sea crearlas.
            5. Si no entendés la orden, devolvé intent UNSUPPORTED y ninguna operación.

            Orden del usuario: %s
            """.formatted(existentes, comando);
    }
}
