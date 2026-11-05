package com.modelcollab.assistant.ai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Garantiza que la respuesta de la IA se dirija por su nombre a quien preguntó: si el nombre de
 * pila no aparece en los primeros {@value #VENTANA} caracteres, se antepone "{nombre}, ".
 *
 * <p>La comparación no distingue mayúsculas ni acentos ("ANDRÉS" vale por "Andres") y es por
 * palabra completa, para que "Ana" no se dé por presente dentro de "mañana".
 */
public final class AddressingGuard {

    public static final int VENTANA = 80;

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern CORCHETES = Pattern.compile("[\\[\\]]");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private AddressingGuard() {}

    public static String apply(String respuesta, String nombreDePila) {
        if (respuesta == null || nombreDePila == null || nombreDePila.isBlank()) {
            return respuesta;
        }
        String nombre = nombreDePila.strip();
        String inicio = respuesta.substring(0, Math.min(VENTANA, respuesta.length()));
        Pattern palabra = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizar(nombre)) + "(?![\\p{L}\\p{N}])");
        if (palabra.matcher(normalizar(inicio)).find()) {
            return respuesta;
        }
        return nombre + ", " + respuesta.stripLeading();
    }

    /** Primera palabra del nombre completo, sin corchetes; vacío si no hay. */
    public static String nombreDePila(String nombreCompleto) {
        if (nombreCompleto == null) {
            return "";
        }
        String limpio = CORCHETES.matcher(nombreCompleto).replaceAll(" ").strip();
        if (limpio.isEmpty()) {
            return "";
        }
        return ESPACIOS.split(limpio, 2)[0];
    }

    static String normalizar(String texto) {
        return MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }
}
