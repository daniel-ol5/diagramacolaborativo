package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.ai.service.AiProperties;
import com.modelcollab.ai.service.ModeloConRespaldo;
import com.modelcollab.ai.service.ReintentoAnteSaturacion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Si el modelo principal no contesta, contesta el de respaldo.
 *
 * <h2>El caso que este archivo existe para cubrir</h2>
 *
 * <p>Esta semana {@code gemini-2.0-flash} empezó a devolver <b>404 "no longer available"</b> de un
 * día para el otro. Google retira modelos sin aviso, y los {@code preview} más que ninguno — y el
 * principal de hoy <b>es</b> un preview.
 *
 * <p>Si eso vuelve a pasar antes de la defensa, el sistema recibe un 404 y, sin esta clase, no
 * activaría el respaldo: CU16 y CU17 se caen enteros sin que nada los cubra. Es el fallo que no
 * avisa.
 */
class ModeloConRespaldoTest {

    private static final AiProperties PROPIEDADES =
            new AiProperties("clave", "principal", "respaldo", null, null);

    private final List<Duration> esperas = new ArrayList<>();
    private final ModeloConRespaldo conRespaldo =
            new ModeloConRespaldo(PROPIEDADES, new ReintentoAnteSaturacion(esperas::add));

    private static HttpClientErrorException noExiste() {
        return HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", null,
                "models/x is not found or no longer available".getBytes(), null);
    }

    private static HttpClientErrorException cuotaAgotada() {
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, new byte[0], null);
    }

    private static HttpServerErrorException saturado() {
        return HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", null, new byte[0], null);
    }

    @Test
    @DisplayName("Si el principal ya no existe (404), contesta el respaldo")
    void el404TambienCaeAlRespaldo() {
        List<String> llamados = new ArrayList<>();

        String r = conRespaldo.pedir("el asistente", modelo -> {
            llamados.add(modelo);
            if (modelo.equals("principal")) {
                throw noExiste();
            }
            return "el plan";
        });

        assertThat(r).isEqualTo("el plan");
        // UNA sola vez al principal: repetir un 404 no lo revive, y cada repetición es tiempo
        // que la persona pasa esperando el mismo error.
        assertThat(llamados).containsExactly("principal", "respaldo");
        assertThat(esperas).as("un 404 no lleva escalera de reintentos").isEmpty();
    }

    @Test
    @DisplayName("Y NO se vuelve a intentar con el principal: un modelo retirado no vuelve")
    void elPrincipalRetiradoNoSeVuelveAIntentar() {
        conRespaldo.pedir("el asistente", modelo -> {
            if (modelo.equals("principal")) {
                throw noExiste();
            }
            return "el plan";
        });
        assertThat(conRespaldo.principalRetirado()).isTrue();

        List<String> segunda = new ArrayList<>();
        conRespaldo.pedir("el asistente", modelo -> {
            segunda.add(modelo);
            return "otro plan";
        });

        // Sin esto, cada petición pagaría una llamada perdida y su latencia para siempre: el
        // asistente pasaría de tardar tres segundos a tardar quince, en silencio.
        assertThat(segunda).containsExactly("respaldo");
    }

    @Test
    @DisplayName("Un 429 va al respaldo: la cuota es por modelo y no se repone insistiendo")
    void laCuotaCaeAlRespaldo() {
        List<String> llamados = new ArrayList<>();

        String r = conRespaldo.pedir("el asistente", modelo -> {
            llamados.add(modelo);
            if (modelo.equals("principal")) {
                throw cuotaAgotada();
            }
            return "el plan";
        });

        assertThat(r).isEqualTo("el plan");
        // SIN ESCALERA, igual que el 404. Se habia puesto una espera mas larga razonando que
        // insistir rapido contra una cuota la empeora; es cierto, pero la conclusion correcta
        // era que la escalera entera es tiempo perdido. Medido: con el principal sin cupo, cada
        // comando pagaba 12 segundos de reintentos antes de caer al respaldo.
        assertThat(llamados).containsExactly("principal", "respaldo");
        assertThat(esperas).as("un 429 no se reintenta: se cambia de modelo").isEmpty();
    }

    @Test
    @DisplayName("Y el principal DESCANSA: no se le paga una llamada perdida a cada comando")
    void elPrincipalSinCuotaDescansa() {
        conRespaldo.pedir("el asistente", modelo -> {
            if (modelo.equals("principal")) {
                throw cuotaAgotada();
            }
            return "el plan";
        });

        List<String> segunda = new ArrayList<>();
        conRespaldo.pedir("el asistente", modelo -> {
            segunda.add(modelo);
            return "otro plan";
        });

        // Sin esto, con el principal sin cupo, TODOS los comandos siguientes vuelven a
        // intentarlo y vuelven a perder esa llamada. A diferencia del 404, el descanso vence:
        // la cuota se repone por ventana de tiempo y el principal vuelve solo.
        assertThat(segunda).containsExactly("respaldo");
        assertThat(conRespaldo.principalRetirado())
                .as("quedarse sin cuota NO es lo mismo que desaparecer")
                .isFalse();
    }

    @Test
    @DisplayName("Un 503 primero reintenta contra el mismo modelo, y recién después cambia")
    void laSaturacionPrimeroReintenta() {
        List<String> llamados = new ArrayList<>();

        conRespaldo.pedir("el asistente", modelo -> {
            llamados.add(modelo);
            if (modelo.equals("principal")) {
                throw saturado();
            }
            return "el plan";
        });

        // La capacidad vuelve sola; por eso vale insistir contra el mismo modelo antes de
        // cambiar. Es la diferencia con el 429 y con el 404.
        assertThat(llamados.stream().filter("principal"::equals).count())
                .isEqualTo(ReintentoAnteSaturacion.INTENTOS);
        assertThat(llamados).endsWith("respaldo");
    }

    @Test
    @DisplayName("Un 400 no cambia de modelo: la petición está mal y el respaldo la rechazaría igual")
    void loQueEsCulpaNuestraNoCambiaDeModelo() {
        List<String> llamados = new ArrayList<>();

        assertThatThrownBy(() -> conRespaldo.pedir("el asistente", modelo -> {
            llamados.add(modelo);
            throw HttpClientErrorException.create(
                    HttpStatus.BAD_REQUEST, "Bad Request", null, new byte[0], null);
        })).isInstanceOf(HttpClientErrorException.class);

        // Es la mitad que impide que esto se vuelva "ante cualquier error, probá con el otro":
        // una petición mal armada falla igual en los dos, y gastar cuota del respaldo para
        // confirmarlo deja sin respaldo al siguiente que sí lo necesite.
        assertThat(llamados).containsExactly("principal");
    }
}
