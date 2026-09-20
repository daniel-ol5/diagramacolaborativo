package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.ai.service.ReintentoAnteSaturacion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Un 503 de Gemini no puede matar el comando.
 *
 * <h2>El hecho que hay detrás</h2>
 *
 * <p>La primera corrida de CU16 contra el modelo real devolvió <b>503 intermitente</b>, con el
 * mensaje de Google diciendo <i>"Please try again later"</i> — un error hecho para reintentarse.
 * Lo tratábamos como definitivo.
 *
 * <p>Medido el 19/09: cinco llamadas con una consulta de 27 bytes dieron 2 de 5, y cinco con una
 * de 1675 bytes dieron 2 de 5 también. <b>La tasa no depende del tamaño</b>, así que no es que
 * nuestra petición sea pesada: el servicio va y viene. Minutos después, cuatro seguidas en 200.
 * Esa es la forma de fallo que un reintento arregla.
 *
 * <h2>Lo que este archivo NO deja pasar</h2>
 *
 * <p>Que reintentar se convierta en aplicar dos veces. Esto envuelve <b>sólo la llamada al
 * modelo</b>, antes de que se aplique nada; el test de los errores que no se reintentan es el que
 * impide que alguien amplíe el envoltorio "por las dudas" hasta cubrir la parte que sí muta.
 */
class ReintentoAnteSaturacionTest {

    /** Un reloj que no duerme: anota cuánto le pidieron dormir y sigue. */
    private final List<Duration> esperas = new ArrayList<>();
    private final ReintentoAnteSaturacion reintento =
            new ReintentoAnteSaturacion(esperas::add);

    private static HttpServerErrorException saturado() {
        return HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                null, "This model is currently experiencing high demand".getBytes(), null);
    }

    private static HttpClientErrorException cuotaAgotada() {
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                null, "You exceeded your current quota".getBytes(), null);
    }

    private static HttpClientErrorException peticionMala() {
        return HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null, new byte[0], null);
    }

    @Test
    @DisplayName("Un 503 y después éxito: el comando sale adelante")
    void elSegundoIntentoSalva() {
        AtomicInteger llamadas = new AtomicInteger();

        String resultado = reintento.ejecutar("el asistente", () -> {
            if (llamadas.incrementAndGet() == 1) {
                throw saturado();
            }
            return "el plan";
        });

        assertThat(resultado).isEqualTo("el plan");
        assertThat(llamadas.get()).as("tiene que haber reintentado").isEqualTo(2);
        assertThat(esperas).as("y haber esperado antes de reintentar").hasSize(1);
    }

    @Test
    @DisplayName("La espera CRECE: insistir al mismo ritmo es parte del problema")
    void laEsperaCrece() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> reintento.ejecutar("el asistente", () -> {
            llamadas.incrementAndGet();
            throw saturado();
        })).isInstanceOf(HttpServerErrorException.class);

        assertThat(llamadas.get()).isEqualTo(ReintentoAnteSaturacion.INTENTOS);
        assertThat(esperas).hasSize(ReintentoAnteSaturacion.INTENTOS - 1);
        assertThat(esperas.get(1))
                .as("la segunda espera tiene que ser mayor que la primera")
                .isGreaterThan(esperas.get(0));
    }

    @Test
    @DisplayName("Un 429 NO se reintenta: la cuota no se repone insistiendo")
    void laCuotaNoSeReintenta() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> reintento.ejecutar("el asistente", () -> {
            llamadas.incrementAndGet();
            throw cuotaAgotada();
        })).isInstanceOf(HttpClientErrorException.class);

        // CORRECCION a la primera version, que le ponia una espera MAS LARGA que al 503
        // razonando que insistir rapido contra una cuota la empeora. Es cierto, pero la
        // conclusion correcta era otra: la escalera entera es tiempo perdido, porque la cuota se
        // repone por ventana de tiempo y no por paciencia. Medido el 20/09 con el principal sin
        // cupo: cada comando pagaba 12 segundos de reintentos y tardaba 16 s en vez de 4.
        //
        // Quien resuelve un 429 es el modelo de respaldo. Ver ModeloConRespaldo.
        assertThat(llamadas.get()).as("tiene que llamar UNA sola vez").isEqualTo(1);
        assertThat(esperas).as("y no esperar nada").isEmpty();
    }

    @Test
    @DisplayName("Un error de RED y después éxito: la conexión de Daniel se tropieza y sigue")
    void unTropezonDeRedNoMataElComando() {
        AtomicInteger llamadas = new AtomicInteger();

        String resultado = reintento.ejecutar("el asistente", () -> {
            if (llamadas.incrementAndGet() == 1) {
                throw new ResourceAccessException(
                        "I/O error", new java.net.SocketTimeoutException("connect timed out"));
            }
            return "el plan";
        });

        // Medido el 19/09 contra la conexión real: el primer intento agotó 20 s sin conectar y
        // el segundo salió en 8,8 s. Un solo reintento salva ese comando, y el 23 la red del
        // lugar de la defensa no la controlamos.
        assertThat(resultado).isEqualTo("el plan");
        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Sin red del todo, se acota: no se cuelga 45 segundos tapando lo evidente")
    void sinRedSeAcota() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> reintento.ejecutar("el asistente", () -> {
            llamadas.incrementAndGet();
            throw new ResourceAccessException("I/O error", new java.io.IOException("sin ruta"));
        })).isInstanceOf(ResourceAccessException.class);

        // Un corte total y un tropezón llegan como la MISMA excepción, así que no se pueden
        // separar. Se elige reintentar igual porque un corte total ya es evidente —el lienzo
        // está desconectado, la aplicación se sirve por esa misma red— mientras que el tropezón
        // es invisible. Lo que se acota es el costo de equivocarse.
        assertThat(llamadas.get()).isEqualTo(ReintentoAnteSaturacion.INTENTOS);
        assertThat(esperas.stream().mapToLong(Duration::toMillis).sum())
                .as("con red caída la espera añadida tiene que ser chica")
                .isLessThan(5000);
    }

    @Test
    @DisplayName("Un 400 NO se reintenta: es culpa nuestra y no va a cambiar")
    void loQueEsCulpaNuestraNoSeReintenta() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> reintento.ejecutar("el asistente", () -> {
            llamadas.incrementAndGet();
            throw peticionMala();
        })).isInstanceOf(HttpClientErrorException.class);

        // Es la mitad que impide que esto se vuelva "reintentar todo". Un 404 de un modelo dado
        // de baja —que es uno de los errores que ya nos pasó— no se arregla repitiéndolo: sólo
        // haría esperar al usuario tres veces el mismo fallo y gastaría cuota.
        assertThat(llamadas.get()).as("tiene que haber llamado UNA sola vez").isEqualTo(1);
        assertThat(esperas).as("y no haber esperado nada").isEmpty();
    }

    @Test
    @DisplayName("Si no se recupera, el error sale tal cual y el usuario se entera")
    void alFinalDiceLaVerdad() {
        assertThatThrownBy(() -> reintento.ejecutar("el asistente", () -> {
            throw saturado();
        }))
                // AiCommandService lo traduce a IA_CAIDA, 502. Lo que importa acá es que NO se
                // lo trague: un fallo definitivo tiene que seguir siendo un fallo.
                .isInstanceOf(HttpServerErrorException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("Los intentos están acotados: la aplicación no se cuelga esperando")
    void estaAcotado() {
        AtomicInteger llamadas = new AtomicInteger();

        assertThatThrownBy(() -> reintento.ejecutar("el asistente", () -> {
            llamadas.incrementAndGet();
            throw saturado();
        })).isInstanceOf(HttpServerErrorException.class);

        // Del otro lado hay alguien esperando a que su comando dictado haga algo. Un reintento
        // sin tope convierte "el asistente no anda" en "la aplicación se colgó", que es peor.
        assertThat(llamadas.get()).isLessThanOrEqualTo(ReintentoAnteSaturacion.INTENTOS);
        assertThat(esperas.stream().mapToLong(Duration::toMillis).sum())
                .as("la espera total no puede pasar del plazo")
                .isLessThanOrEqualTo(ReintentoAnteSaturacion.PLAZO.toMillis());
    }
}
