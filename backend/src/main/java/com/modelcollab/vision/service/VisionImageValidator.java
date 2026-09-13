package com.modelcollab.vision.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Comprueba la foto antes de mandarla a ningún lado.
 *
 * <h2>El tipo declarado por el cliente no es evidencia</h2>
 *
 * <p>La cabecera {@code Content-Type} de una parte multipart la escribe quien sube el archivo, y
 * por lo tanto no dice nada sobre el contenido: cualquiera puede mandar un ejecutable diciendo
 * que es un PNG. Por eso el tipo se decide <b>leyendo los primeros bytes</b> y el declarado se
 * ignora por completo.
 *
 * <p>No es paranoia abstracta: estos bytes se reenvían a un servicio externo, y lo que se le
 * manda a un tercero es responsabilidad nuestra. El tipo que sale de acá es el que se declara río
 * abajo, así que tiene que ser el real.
 *
 * <h2>Y no se guarda</h2>
 *
 * <p>La imagen se procesa en memoria y se descarta. No hay almacenamiento, no hay ruta, no hay
 * limpieza pendiente. Son fotos de pizarras ajenas sacadas en una oficina: no tenemos ningún
 * motivo para conservarlas y sí varios para no hacerlo. Lo que no se guarda no se filtra.
 */
@Component
public class VisionImageValidator {

    /**
     * Ocho megabytes. Una foto de celular ronda los tres o cuatro, así que da margen de sobra
     * sin dejar que una petición sola se lleve puesta la memoria del servidor.
     */
    public static final int MAXIMO_DE_BYTES = 8 * 1024 * 1024;

    /** Los tipos que el modelo multimodal acepta, cada uno con su firma en los primeros bytes. */
    private static final Map<String, byte[]> FIRMAS = new LinkedHashMap<>();

    static {
        FIRMAS.put("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        FIRMAS.put("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        FIRMAS.put("image/gif", new byte[] {'G', 'I', 'F', '8'});
    }

    /** WEBP es "RIFF" + 4 bytes de tamaño + "WEBP", así que no entra en una firma contigua. */
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    /**
     * @return el tipo MIME deducido del contenido
     * @throws ImagenRechazadaException si está vacía, es muy grande o no es una imagen soportada
     */
    public String tipoDe(byte[] imagen) {
        if (imagen == null || imagen.length == 0) {
            throw new ImagenRechazadaException("No llegó ninguna imagen.");
        }
        if (imagen.length > MAXIMO_DE_BYTES) {
            throw new ImagenRechazadaException(
                    "La imagen pesa " + (imagen.length / (1024 * 1024)) + " MB y el máximo son "
                            + (MAXIMO_DE_BYTES / (1024 * 1024)) + " MB. Sacale resolución.");
        }
        for (Map.Entry<String, byte[]> firma : FIRMAS.entrySet()) {
            if (empiezaCon(imagen, firma.getValue(), 0)) {
                return firma.getKey();
            }
        }
        if (empiezaCon(imagen, RIFF, 0) && empiezaCon(imagen, WEBP, 8)) {
            return "image/webp";
        }
        throw new ImagenRechazadaException(
                "El archivo no es una imagen que sepamos leer. Se aceptan PNG, JPEG, WEBP y GIF.");
    }

    private static boolean empiezaCon(byte[] datos, byte[] firma, int desde) {
        if (datos.length < desde + firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (datos[desde + i] != firma[i]) {
                return false;
            }
        }
        return true;
    }

    /** La imagen que subieron no sirve, y el que la subió puede arreglarlo. */
    public static class ImagenRechazadaException extends RuntimeException {
        public ImagenRechazadaException(String mensaje) {
            super(mensaje);
        }
    }
}
