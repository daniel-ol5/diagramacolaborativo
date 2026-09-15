package com.modelcollab.generator.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Plural, género y artículos en castellano para los nombres de las entidades.
 *
 * <h2>Por qué esto existe</h2>
 *
 * <p>Las frases que disparan una intención se dictan en voz alta: <i>"muestra las citas"</i>,
 * <i>"crea un pago"</i>. Si salieran de una plantilla tonta darían <i>"muestra las citas"</i> y
 * también <i>"muestra las pagos"</i>, y una app que le habla mal al usuario en la demostración
 * se nota más que un error de fondo.
 *
 * <p>Es una <b>heurística</b>, no morfología completa del castellano, y conviene que esté dicho:
 * acierta en los nombres que una persona le pone a una entidad de negocio y falla en los casos
 * raros. Lo importante es que lo que falla es <b>cosmético</b> — una frase de disparo de más o
 * con el artículo cambiado no rompe nada, porque el reconocedor compara contra varias.
 *
 * <h2>camelCase se parte en palabras</h2>
 *
 * <p>{@code MovimientoStock} se dicta "movimiento stock", no "movimientostock". Y el género y el
 * plural los decide <b>la última palabra</b>, que es la que manda en castellano: "el movimiento
 * stock" pero "la orden compra".
 */
@Component
public class SpanishNounInflector {

    /**
     * Terminaciones femeninas más allá de la {@code -a}. Sin ellas, "Publicacion" y "Ciudad"
     * saldrían como masculinas y el artículo quedaría mal en todas sus frases.
     */
    private static final Set<String> FINALES_FEMENINOS =
            Set.of("cion", "sion", "dad", "tad", "tud", "umbre", "ez", "triz", "itis", "sis");

    /**
     * Las de origen griego terminadas en {@code -ma} y compañía: son masculinas pese a la
     * {@code -a} final. Es la excepción que uno descubre cuando el sistema dice "la problema".
     */
    private static final Set<String> MASCULINOS_EN_A = Set.of(
            "problema", "sistema", "tema", "idioma", "programa", "diagrama", "esquema",
            "mapa", "dia", "clima", "planeta", "sofa", "tranvia", "informe");

    /** Vocales sin tilde: deciden si el plural es {@code +s} o {@code +es}. */
    private static final String VOCALES = "aeiou";

    /** "MovimientoStock" → "movimiento stock". Lo que se dicta, no lo que se compila. */
    public String enPalabras(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return "";
        }
        String conEspacios = nombre.strip()
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[_-]+", " ")
                .replaceAll("\\s+", " ");
        return conEspacios.toLowerCase(Locale.ROOT).strip();
    }

    /** Plural de un nombre completo; sólo cambia la última palabra. */
    public String plural(String nombre) {
        String palabras = enPalabras(nombre);
        int corte = palabras.lastIndexOf(' ');
        String ultima = corte < 0 ? palabras : palabras.substring(corte + 1);
        String resto = corte < 0 ? "" : palabras.substring(0, corte + 1);
        return resto + pluralDeUnaPalabra(ultima);
    }

    private String pluralDeUnaPalabra(String palabra) {
        if (palabra.isEmpty()) {
            return palabra;
        }
        String sinTildes = sinTildes(palabra);
        char ultima = sinTildes.charAt(sinTildes.length() - 1);

        if (ultima == 'z') {
            // "vez" → "veces": la z se vuelve c antes de la e.
            return palabra.substring(0, palabra.length() - 1) + "ces";
        }
        if (ultima == 's' || ultima == 'x') {
            // "crisis", "fax": se quedan igual en plural si no son agudas. Dejarlas quietas
            // acierta mucho más seguido que agregarles "es".
            return palabra;
        }
        if (VOCALES.indexOf(ultima) >= 0) {
            return palabra + "s";
        }
        return palabra + "es";
    }

    /** Si el nombre es femenino, para elegir el artículo. */
    public boolean esFemenino(String nombre) {
        String palabras = enPalabras(nombre);
        int corte = palabras.lastIndexOf(' ');
        String ultima = sinTildes(corte < 0 ? palabras : palabras.substring(corte + 1));

        if (ultima.isEmpty()) {
            return false;
        }
        if (MASCULINOS_EN_A.contains(ultima)) {
            return false;
        }
        if (FINALES_FEMENINOS.stream().anyMatch(ultima::endsWith)) {
            return true;
        }
        return ultima.endsWith("a");
    }

    /** "un" / "una". */
    public String articuloIndefinido(String nombre) {
        return esFemenino(nombre) ? "una" : "un";
    }

    /** "el" / "la". */
    public String articuloDefinido(String nombre) {
        return esFemenino(nombre) ? "la" : "el";
    }

    /** "los" / "las". */
    public String articuloDefinidoPlural(String nombre) {
        return esFemenino(nombre) ? "las" : "los";
    }

    /**
     * Sin tildes y en minúsculas.
     *
     * <p>Hace falta para decidir el plural: "canción" termina en n con tilde en la o, y comparar
     * el carácter final contra la lista de vocales daría lo mismo — pero "café" sí cambia, y las
     * reglas se escriben sobre la forma sin tildes para no tener que duplicarlas.
     */
    private static String sinTildes(String texto) {
        return Normalizer.normalize(texto.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /** MAYÚSCULAS sin tildes ni espacios: lo que sirve como nombre de intención. */
    public String comoIdentificador(String nombre) {
        return sinTildes(enPalabras(nombre))
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
    }
}
