package com.modelcollab.ai.service;

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Llama al modelo principal y, si no se puede, al de respaldo.
 *
 * <h2>Tres fallos distintos que necesitan tres respuestas distintas</h2>
 *
 * <ul>
 *   <li><b>503 / fallo de red</b> — transitorio. Escalera de reintentos contra el
 *       <i>mismo</i> modelo; si no cede, el respaldo.</li>
 *   <li><b>429</b> — la cuota se agotó. Reintentar contra el mismo modelo no la repone, así que
 *       lo que recupera es el respaldo. Medido el 20/09: un modelo devolvía 429 cuatro de cuatro
 *       mientras otro devolvía 200 cuatro de cuatro, <b>con la misma clave y en el mismo
 *       minuto</b>. La cuota es por modelo.</li>
 *   <li><b>404</b> — <b>el modelo dejó de existir.</b> No es transitorio y no vuelve: se va
 *       derecho al respaldo, sin escalera, porque repetirlo no lo revive.</li>
 * </ul>
 *
 * <h2>Por qué el 404 merece su propio trato, con un caso real de esta semana</h2>
 *
 * <p>{@code gemini-2.0-flash} —el modelo que estaba configurado— empezó a devolver <b>404 "no
 * longer available"</b> de un día para el otro. Google retira modelos sin aviso, y los
 * {@code preview} más que ninguno.
 *
 * <p>Con el principal apuntando a un preview, eso puede volver a pasar <b>entre hoy y la
 * defensa</b>. Sin esta rama, el sistema recibiría un 404, no activaría el respaldo, y el
 * asistente moriría entero sin que nada lo cubra.
 *
 * <h2>Y una vez que se cae, no se vuelve a intentar</h2>
 *
 * <p>Un modelo retirado <b>no vuelve</b>, así que seguir llamándolo en cada petición sería pagar
 * una llamada perdida —y su latencia— para siempre. Después del primer 404 queda anotado y todas
 * las peticiones siguientes van directo al respaldo.
 *
 * <p><b>Y se grita en el registro.</b> Sin eso, el sistema seguiría andando por el respaldo y
 * nadie se enteraría hasta que el respaldo también fallara — que es la peor manera de descubrirlo.
 */
public class ModeloConRespaldo {

    private static final Logger log = LoggerFactory.getLogger(ModeloConRespaldo.class);

    private final AiProperties propiedades;
    private final ReintentoAnteSaturacion reintento;

    /** volatile: lo escribe el hilo que reciba el primer 404 y lo leen todos los demás. */
    private volatile boolean principalRetirado;

    /** Hasta cuándo se deja descansar al principal después de un 429. 0 = no está descansando. */
    private volatile long principalDescansaHasta;

    public ModeloConRespaldo(AiProperties propiedades, ReintentoAnteSaturacion reintento) {
        this.propiedades = propiedades;
        this.reintento = reintento;
    }

    /**
     * @param queEs para el registro: "El asistente", "La lectura de fotos"
     * @param llamada recibe el nombre del modelo y devuelve la respuesta cruda
     */
    public String pedir(String queEs, Function<String, String> llamada) {
        if (principalRetirado || System.nanoTime() < principalDescansaHasta) {
            return llamada.apply(propiedades.modeloDeRespaldo());
        }

        try {
            return reintento.ejecutar(queEs, () -> llamada.apply(propiedades.model()));
        } catch (HttpClientErrorException.NotFound e) {
            principalRetirado = true;
            log.error("EL MODELO PRINCIPAL YA NO EXISTE: {} devolvió 404. Se pasa a {} para "
                            + "todas las peticiones. Hay que actualizar GEMINI_MODEL.",
                    propiedades.model(), propiedades.modeloDeRespaldo());
            return llamada.apply(propiedades.modeloDeRespaldo());
        } catch (RuntimeException e) {
            if (!ReintentoAnteSaturacion.esSaturacion(e)) {
                throw e;
            }
            if (ReintentoAnteSaturacion.esCuotaAgotada(e)) {
                // La cuota no vuelve en segundos: se lo deja descansar en vez de pagarle una
                // llamada perdida a cada comando. Medido el 20/09: sin esto, con el principal
                // sin cupo, cada comando tardaba entre 16 y 45 s en lugar de 4.
                principalDescansaHasta =
                        System.nanoTime() + ReintentoAnteSaturacion.DESCANSO_POR_CUOTA.toNanos();
                log.warn("{} se quedó sin cuota; se usa {} durante los próximos {} s",
                        propiedades.model(), propiedades.modeloDeRespaldo(),
                        ReintentoAnteSaturacion.DESCANSO_POR_CUOTA.toSeconds());
            }
            // Una sola llamada al respaldo, sin escalera: si está sano contesta enseguida, y si
            // no lo está, corresponde decir la verdad en vez de hacer esperar más.
            log.warn("{} no respondió con {}; se recurre al respaldo ({})",
                    queEs, propiedades.model(), propiedades.modeloDeRespaldo());
            return llamada.apply(propiedades.modeloDeRespaldo());
        }
    }

    /** Para el arranque y para los tests: si ya se cayó al respaldo de forma permanente. */
    public boolean principalRetirado() {
        return principalRetirado;
    }
}
