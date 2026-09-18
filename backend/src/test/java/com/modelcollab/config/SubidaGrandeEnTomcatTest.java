package com.modelcollab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Un archivo por encima del límite de multipart responde <b>413 con un cuerpo que se puede
 * leer</b>.
 *
 * <h2>Por qué hace falta un test con Tomcat de verdad</h2>
 *
 * <p>Este bug ya estuvo suelto una vez. El manejador existía desde CU17, pero vivía como
 * {@code @ExceptionHandler} dentro de {@code VisionController} y <b>no se ejecutaba nunca</b>:
 * {@link org.springframework.web.multipart.MaxUploadSizeExceededException} la lanza el resolvedor
 * de multipart <b>antes</b> de que se elija un controlador, así que no hay ninguno al que
 * atribuirla. Sólo la ve un {@code @ControllerAdvice}.
 *
 * <p>El resultado era exactamente el síntoma que ese manejador decía evitar: <b>413 con el cuerpo
 * vacío</b>. La persona sube una foto, no pasa nada, y no hay ningún texto que leer.
 *
 * <p><b>Ningún test lo podía ver</b>, y esa es la razón de que este archivo exista. El camino que
 * falla está en el resolvedor de multipart, no en código nuestro: hace falta un servidor servlet
 * de verdad para ejercitarlo. Lo encontró un script contra el servidor levantado, pero un script
 * es algo que alguien tiene que acordarse de correr — y esto ya volvió una vez.
 *
 * <h2>Arranca un contexto mínimo, y a propósito</h2>
 *
 * <p>Este es el <b>único</b> arnés del proyecto que levanta Tomcat, y no arrastra la base: se
 * excluyen el DataSource, JPA, Flyway y la seguridad. Es lo que mantiene en pie la propiedad de
 * la que depende la CI — <b>la suite pasa sin PostgreSQL alcanzable</b> — y evita que el arnés
 * más lento del día a día se vuelva el que todos esperan.
 *
 * <p>Lo que sí es real es lo que importa: el conector de Tomcat, el resolvedor de multipart, los
 * límites de {@code application.yml} y el {@code @ControllerAdvice} de producción.
 *
 * <h2>El tamaño sale de la configuración, no de un número escrito acá</h2>
 *
 * <p>La carga se arma a partir de {@link MultipartProperties}, así que si mañana alguien sube o
 * baja el límite, este test sigue probando lo que dice probar en vez de quedar midiendo un
 * archivo que ya no lo supera.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = SubidaGrandeEnTomcatTest.ContextoMinimo.class)
class SubidaGrandeEnTomcatTest {

    /**
     * Lo mínimo para que exista Tomcat con multipart: sin base, sin JPA, sin Flyway y sin
     * seguridad. El manejador que se prueba es el <b>de producción</b>, importado tal cual.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
    })
    @Import({SubidaDemasiadoGrandeHandler.class, ContextoMinimo.Receptor.class})
    static class ContextoMinimo {

        /**
         * Un receptor de subidas cualquiera.
         *
         * <p>No es el de visión y no tiene por qué serlo: <b>el fallo nunca llega al
         * controlador</b>. Usar el de verdad obligaría a arrastrar media aplicación para probar
         * un camino que se corta antes de tocarla.
         */
        @RestController
        static class Receptor {

            @PostMapping("/prueba/subida")
            public String recibir(@RequestParam("image") MultipartFile archivo) {
                return "recibido:" + archivo.getSize();
            }
        }
    }

    /** El separador de lineas que exige HTTP, escrito asi para que no lo toque ningun editor. */
    private static final String CRLF = "" + (char) 13 + (char) 10;

    private static String comillas(String valor) {
        return (char) 34 + valor + (char) 34;
    }

    @LocalServerPort private int puerto;
    @Autowired private MultipartProperties limites;

    @Test
    @DisplayName("Un archivo por encima del limite da 413 y el cuerpo EXPLICA por que")
    void elRechazoTraeTexto() throws Exception {
        long limite = limites.getMaxFileSize().toBytes();
        HttpResponse<String> respuesta = subir((int) limite + 512 * 1024);

        assertThat(respuesta.statusCode()).isEqualTo(413);

        // ESTA es la asercion por la que existe el archivo. El estado ya salia bien cuando el
        // manejador no se ejecutaba; lo que faltaba era el cuerpo, y un 413 sin cuerpo deja a
        // quien subio la foto sin nada que leer.
        assertThat(respuesta.body())
                .as("413 con el cuerpo vacio: el @ControllerAdvice no se esta ejecutando")
                .isNotBlank()
                .contains("MB");
    }

    @Test
    @DisplayName("Un archivo por debajo del limite si llega al controlador")
    void loQueEntraLlega() throws Exception {
        // Sin este caso, el de arriba pasaria igual con un servidor que rechaza TODO, y el arnes
        // no estaria midiendo el limite sino la existencia de un error.
        int tamanio = 64 * 1024;
        HttpResponse<String> respuesta = subir(tamanio);

        assertThat(respuesta.statusCode()).isEqualTo(200);
        assertThat(respuesta.body()).isEqualTo("recibido:" + tamanio);
    }

    /**
     * Manda un multipart con el cuerpo entero armado y su {@code Content-Length}.
     *
     * <p>No es un detalle de comodidad: <b>con codificacion fragmentada este test no se puede
     * escribir</b>. Cuando el cuerpo va en trozos, Tomcat corta la peticion al pasarse del
     * limite, se traga solo los 2 MB que dice {@code maxSwallowSize} y <b>cierra la conexion</b>
     * — el cliente recibe un corte de red y nunca llega a leer el 413. Con el largo declarado, el
     * servidor sabe de entrada cuanto viene y contesta limpio.
     *
     * <p>Eso tambien es cierto contra el servidor de verdad, y conviene tenerlo presente: una
     * subida MUY por encima del limite puede llegarle al cliente como "se corto la conexion" en
     * vez de como el mensaje que explica el tamanio. Lo descubrio este mismo arnes, que con el
     * cliente de Spring —que fragmenta— fallaba con un error de red en lugar de un 413.
     */
    private HttpResponse<String> subir(int bytes) throws Exception {
        String frontera = "----modelcollab" + System.nanoTime();
        String cabeza = "--" + frontera + CRLF
                + "Content-Disposition: form-data; name=" + comillas("image")
                + "; filename=" + comillas("pizarra.jpg") + CRLF
                + "Content-Type: image/jpeg" + CRLF + CRLF;
        String cola = CRLF + "--" + frontera + "--" + CRLF;


        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        cuerpo.write(cabeza.getBytes(StandardCharsets.UTF_8));
        cuerpo.write(relleno(bytes));
        cuerpo.write(cola.getBytes(StandardCharsets.UTF_8));

        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + puerto + "/prueba/subida"))
                        .header("Content-Type", "multipart/form-data; boundary=" + frontera)
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Bytes con la firma de un JPEG delante, para que se parezca a lo que sube un celular. */
    private static byte[] relleno(int bytes) {
        byte[] datos = new byte[bytes];
        datos[0] = (byte) 0xFF;
        datos[1] = (byte) 0xD8;
        datos[2] = (byte) 0xFF;
        byte[] resto = "pizarra".getBytes(StandardCharsets.UTF_8);
        for (int i = 3; i < bytes; i++) {
            datos[i] = resto[i % resto.length];
        }
        return datos;
    }
}
