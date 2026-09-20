package com.modelcollab.ai.service;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Reintenta una llamada al modelo cuando el servicio está saturado.
 *
 * <h2>Por qué hace falta, medido y no supuesto</h2>
 *
 * <p>La primera corrida de CU16 contra Gemini de verdad dio <b>503 intermitente</b>. El propio
 * mensaje de Google lo dice: <i>"This model is currently experiencing high demand. Spikes in
 * demand are usually temporary. Please try again later."</i> Es un error <b>diseñado para
 * reintentarse</b>, y lo estábamos tratando como definitivo: al usuario le decíamos que el
 * asistente no está disponible y ahí terminaba todo.
 *
 * <h2>Lo que la medición descartó</h2>
 *
 * <p>La sospecha era que el tamaño de la petición nos ponía en una cola más castigada — las
 * pruebas sueltas con una consulta de juguete salían y las de la aplicación, con el diagrama
 * entero, no. <b>Es falso.</b> Midiendo las dos contra el mismo modelo y la misma clave:
 *
 * <table border="1">
 *   <caption>Cinco llamadas de cada una, 19/09</caption>
 *   <tr><th>Consulta</th><th>Tamaño</th><th>Éxitos</th><th>Fallos</th></tr>
 *   <tr><td>chica</td><td>27 bytes</td><td>2/5</td><td>tres 503</td></tr>
 *   <tr><td>grande</td><td>1675 bytes</td><td>2/5</td><td>un 503, dos 429</td></tr>
 * </table>
 *
 * <p>Misma tasa de éxito con 27 bytes que con 1675. <b>No es el tamaño: es el servicio.</b> Y
 * minutos después, cuatro llamadas seguidas dieron 200 las cuatro — o sea que la saturación va y
 * viene en ráfagas, que es exactamente la forma de fallo que un reintento resuelve.
 *
 * <h2>Qué se reintenta y qué no</h2>
 *
 * <ul>
 *   <li><b>503</b> — el servicio está saturado. Se reintenta.</li>
 *   <li><b>429</b> — cuota por minuto agotada. Se reintenta <b>con más espera</b>: insistir
 *       rápido contra una cuota la empeora. También apareció en la medición, y también se
 *       despejó solo.</li>
 *   <li><b>Un error de red</b> —{@link ResourceAccessException}: no conectó, se cortó, agotó el
 *       tiempo— también se reintenta. Medido contra la conexión de Daniel, tres llamadas
 *       seguidas: la primera <b>agotó 20 s sin conectar</b>, la segunda salió en 8,8 s. Falló
 *       una y la siguiente anduvo: un solo reintento habría salvado el comando.</li>
 *   <li><b>Todo lo demás no se reintenta.</b> Un 400, un 401 o un 404 son culpa nuestra —
 *       petición mal armada, clave inválida, modelo que ya no existe— y reintentarlos sólo gasta
 *       cuota y le hace esperar al usuario tres veces el mismo error. El 404 del modelo dado de
 *       baja es justamente uno de los que encontramos: repetirlo no lo habría revivido.</li>
 * </ul>
 *
 * <h2>Por qué reintentar acá es seguro, y dónde NO lo sería</h2>
 *
 * <p>Esto envuelve <b>sólo la llamada al modelo</b>, que es una lectura: se le pide un plan. Las
 * operaciones se aplican después, en {@code AiCommandService}, una vez y con el
 * {@code clientCommandId} de por medio. <b>Un reintento acá no puede aplicar nada dos veces</b>
 * porque en este punto todavía no se aplicó nada.
 *
 * <p>Si algún día alguien mueve este envoltorio para que cubra también la aplicación, eso deja
 * de ser cierto: un comando que aplica y después falla la red se reintentaría entero y duplicaría
 * las clases. El tope de cinco operaciones y la exclusión de los borrados no protegen contra eso.
 *
 * <h2>Acotado a propósito</h2>
 *
 * <p>Tres intentos y un plazo total: del otro lado hay una persona esperando a que su comando
 * dictado haga algo. Un reintento infinito convierte "el asistente no anda" en "la aplicación se
 * colgó", que es peor — la primera se entiende, la segunda no.
 */
public final class ReintentoAnteSaturacion {

    private static final Logger log = LoggerFactory.getLogger(ReintentoAnteSaturacion.class);

    /** El primero más dos reintentos. Con la tasa medida (~40% de éxito), cubre el 78%. */
    public static final int INTENTOS = 3;

    /** Espera tras un 503. La segunda es el doble. */
    public static final Duration ESPERA_SATURADO = Duration.ofMillis(1200);

    /**
     * Cuánto se deja de lado al modelo principal después de un 429.
     *
     * <p>La cuota se repone por ventana de tiempo. Volver a probarlo en cada comando sería pagar
     * una llamada perdida cada vez — con el principal sin cupo, eso convertía comandos de cuatro
     * segundos en comandos de dieciséis. Se lo deja descansar y se vuelve a intentar después.
     */
    public static final Duration DESCANSO_POR_CUOTA = Duration.ofSeconds(90);

    /**
     * Espera tras un error de red: corta, porque el intento fallido ya gastó tiempo por su cuenta.
     *
     * <h2>Corte total contra tropezón, que es la pregunta que hay debajo</h2>
     *
     * <p>Las dos cosas llegan como la misma excepción, y reintentar un corte total sólo retrasa
     * el mismo error. Se elige reintentar igual, por dos razones:
     *
     * <p><b>Un corte total no necesita que se lo digamos: ya es evidente.</b> Esta aplicación se
     * sirve por la misma red, y la colaboración va por WebSocket sobre esa red. Si no hay
     * conexión, el usuario ya está viendo el lienzo desconectado — el comando dictado no es por
     * donde se entera. El caso que sí es invisible es el tropezón: todo anda, y justo esa llamada
     * cae.
     *
     * <p><b>Y el costo de equivocarse está acotado por el tiempo de conexión</b>, que es de
     * {@link #ESPERA_RED} × intentos más unos pocos segundos, no los 90 que darían tres esperas
     * de lectura completas. Por eso {@code GeminiAiModelClient} separa el tiempo de conexión del
     * de lectura: conectar tiene que fallar rápido; pensar puede tardar.
     */
    public static final Duration ESPERA_RED = Duration.ofMillis(700);

    /**
     * Plazo total. Con el tiempo de espera de la llamada en 30 s, tres intentos podrían sumar 90
     * segundos de silencio; esto lo corta.
     */
    public static final Duration PLAZO = Duration.ofSeconds(45);

    /** Se inyecta en los tests para no dormir de verdad. */
    @FunctionalInterface
    public interface Reloj {
        void dormir(Duration cuanto) throws InterruptedException;
    }

    private final Reloj reloj;

    public ReintentoAnteSaturacion() {
        this(Thread::sleep);
    }

    public ReintentoAnteSaturacion(Reloj reloj) {
        this.reloj = reloj;
    }

    /**
     * Ejecuta la llamada, reintentando si el servicio está saturado.
     *
     * @param queEs para el registro: "el asistente", "la lectura de fotos"
     */
    public <T> T ejecutar(String queEs, Supplier<T> llamada) {
        long limite = System.nanoTime() + PLAZO.toNanos();
        RuntimeException ultimo = null;

        for (int intento = 1; intento <= INTENTOS; intento++) {
            try {
                return llamada.get();
            } catch (ResourceAccessException e) {
                ultimo = e;
                Duration espera = ESPERA_RED.multipliedBy(intento);
                if (intento == INTENTOS || System.nanoTime() + espera.toNanos() > limite) {
                    break;
                }
                log.warn("{} no se pudo alcanzar ({}); se reintenta en {} ms",
                        queEs, e.getMostSpecificCause().toString(), espera.toMillis());
                dormir(espera);
            } catch (HttpServerErrorException | HttpClientErrorException e) {
                Duration base = esperaPara(e);
                // Creciente: si el servicio está saturado, insistir al mismo ritmo es parte del
                // problema. La segunda espera es el doble de la primera.
                Duration espera = base == null ? null : base.multipliedBy(intento);
                if (espera == null) {
                    // No es saturación: es nuestro error. Que salga tal cual y rápido.
                    throw e;
                }
                ultimo = e;
                if (intento == INTENTOS || System.nanoTime() + espera.toNanos() > limite) {
                    break;
                }
                log.warn("{} devolvió {} (intento {} de {}); se reintenta en {} ms",
                        queEs, e.getStatusCode(), intento, INTENTOS, espera.toMillis());
                dormir(espera);
            }
        }
        log.warn("{} no respondió después de {} intentos", queEs, INTENTOS);
        throw ultimo;
    }

    /**
     * Si el fallo es "el servicio no esta disponible ahora" y no "la peticion esta mal".
     *
     * <p>Lo usa el cliente para decidir si vale la pena recurrir al modelo de respaldo. Un 400 o
     * un 404 no se arreglan cambiando de modelo — se arreglan arreglando la peticion.
     */
    /** Si el fallo es cuota agotada: no se reintenta, se cambia de modelo. */
    public static boolean esCuotaAgotada(RuntimeException e) {
        return e instanceof HttpClientErrorException c && c.getStatusCode().value() == 429;
    }

    public static boolean esSaturacion(RuntimeException e) {
        if (esCuotaAgotada(e)) {
            return true;
        }
        if (e instanceof ResourceAccessException) {
            return true;
        }
        if (e instanceof HttpServerErrorException || e instanceof HttpClientErrorException) {
            return esperaPara(e) != null;
        }
        return false;
    }

    /** Cuánto esperar antes del próximo intento, o {@code null} si no hay que reintentar. */
    private static Duration esperaPara(RuntimeException e) {
        int codigo = e instanceof HttpServerErrorException s
                ? s.getStatusCode().value()
                : ((HttpClientErrorException) e).getStatusCode().value();

        // EL 429 NO LLEVA ESCALERA, y esto es una correccion a la version anterior.
        //
        // Se habia puesto con una espera mas larga, razonando que insistir rapido contra una
        // cuota la empeora. Es cierto, pero la conclusion correcta era otra: la cuota NO se
        // repone en segundos, asi que la escalera entera es tiempo perdido. Medido el 20/09 con
        // el principal sin cuota: cada comando pagaba 12 SEGUNDOS de reintentos antes de caer al
        // respaldo, y por eso tardaban entre 16 y 45 s en vez de 4.
        //
        // Quien resuelve un 429 es el modelo de respaldo, no la paciencia. Ver ModeloConRespaldo.
        return switch (codigo) {
            case 503 -> ESPERA_SATURADO;
            default -> null;
        };
    }

    private void dormir(Duration cuanto) {
        try {
            reloj.dormir(cuanto);
        } catch (InterruptedException e) {
            // Alguien está apagando el servidor o cancelando la petición. Se respeta la
            // interrupción en vez de tragársela y seguir esperando.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reintento interrumpido", e);
        }
    }
}
