package com.modelcollab.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.vision.service.VisionImageValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La foto que entra, comprobada por su contenido y no por lo que diga el que la sube.
 *
 * <p>Estos bytes se reenvían a un servicio externo. Lo que se le manda a un tercero es
 * responsabilidad nuestra, así que el tipo que sale de acá tiene que ser el real.
 */
class VisionImageValidatorTest {

    private final VisionImageValidator validador = new VisionImageValidator();

    private static byte[] con(int... cabecera) {
        byte[] datos = new byte[64];
        for (int i = 0; i < cabecera.length; i++) {
            datos[i] = (byte) cabecera[i];
        }
        return datos;
    }

    @Test
    @DisplayName("Reconoce PNG, JPEG y GIF por sus primeros bytes")
    void reconoceLosFormatos() {
        assertThat(validador.tipoDe(con(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                .isEqualTo("image/png");
        assertThat(validador.tipoDe(con(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(validador.tipoDe(con('G', 'I', 'F', '8', '9', 'a'))).isEqualTo("image/gif");
    }

    @Test
    @DisplayName("WEBP se reconoce aunque su firma no sea contigua")
    void reconoceWebp() {
        // RIFF + 4 bytes de tamaño + WEBP: si se buscara sólo al principio, nunca coincidiría.
        byte[] webp = con('R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00);
        byte[] marca = "WEBP".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marca, 0, webp, 8, 4);

        assertThat(validador.tipoDe(webp)).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("Un archivo que NO es imagen se rechaza aunque venga disfrazado")
    void loQueNoEsImagen() {
        // El Content-Type de una parte multipart lo escribe quien sube el archivo: cualquiera
        // puede mandar un ejecutable diciendo que es un PNG. Por eso el validador ni lo recibe.
        byte[] ejecutable = con('M', 'Z', 0x90, 0x00);

        assertThatThrownBy(() -> validador.tipoDe(ejecutable))
                .isInstanceOf(VisionImageValidator.ImagenRechazadaException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    @DisplayName("Una imagen vacía o ausente se rechaza")
    void vacia() {
        assertThatThrownBy(() -> validador.tipoDe(null))
                .isInstanceOf(VisionImageValidator.ImagenRechazadaException.class);
        assertThatThrownBy(() -> validador.tipoDe(new byte[0]))
                .isInstanceOf(VisionImageValidator.ImagenRechazadaException.class);
    }

    @Test
    @DisplayName("Una imagen enorme se rechaza con un mensaje que dice qué hacer")
    void demasiadoGrande() {
        byte[] enorme = new byte[VisionImageValidator.MAXIMO_DE_BYTES + 1];
        enorme[0] = (byte) 0x89;
        enorme[1] = 'P';
        enorme[2] = 'N';
        enorme[3] = 'G';

        assertThatThrownBy(() -> validador.tipoDe(enorme))
                .isInstanceOf(VisionImageValidator.ImagenRechazadaException.class)
                // "Es muy grande" sin decir cuánto ni qué hacer deja al usuario probando a ciegas.
                .hasMessageContaining("8 MB")
                .hasMessageContaining("resolución");
    }

    @Test
    @DisplayName("Un archivo más corto que la firma no revienta")
    void archivoMinusculo() {
        assertThatThrownBy(() -> validador.tipoDe(new byte[] {(byte) 0x89, 'P'}))
                .isInstanceOf(VisionImageValidator.ImagenRechazadaException.class);
    }
}
