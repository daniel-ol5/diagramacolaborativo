package com.modelcollab.ai.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * El guardarraíl antialucinación de RF-02.6, en su versión <b>determinista y sin red</b>.
 *
 * <h2>Qué es y qué no es</h2>
 *
 * <p>Esto es un <b>atajo</b>, no la garantía. Reconoce la forma de los pedidos que quieren un
 * dominio entero —"creame un sistema para un banco", "hazme un modelo completo de un hospital"—
 * y los rechaza <b>antes de gastar una llamada al servicio de IA</b>. Que no dependa de la red
 * tiene una consecuencia concreta y buscada: la pregunta que §19 anticipa del tribunal se
 * responde igual con la clave vencida, sin internet o con el servicio caído.
 *
 * <p>La garantía de verdad está más adentro y no es esta clase: el <b>tope duro de 5
 * operaciones</b>, la <b>lista blanca</b> de operaciones que la IA puede pedir y la
 * <b>resolución de nombres contra el diagrama real</b>. Cualquier pedido masivo que se escape de
 * acá muere ahí, porque quince clases no caben en cinco operaciones. Confundir las dos cosas
 * sería el error de siempre: tomar la comprobación barata por la comprobación que sostiene.
 *
 * <h2>Cómo distingue</h2>
 *
 * <p>Un comando legítimo nombra un <b>elemento del diagrama</b>: "la clase Factura", "el atributo
 * total", "relaciona Factura con Cliente". Un pedido de generación nombra un <b>contenedor</b>
 * —un sistema, una aplicación, una base de datos— y ninguna pieza concreta. Esa es la línea:
 * pide un contenedor y no menciona ni una pieza.
 *
 * <p>La lista está calibrada contra los ejemplos del propio RF-02.6 y no contra la intuición.
 * "Modelo" está suelto porque el documento pone <i>"créame un modelo para un hospital"</i> como
 * caso a rechazar, y no es peligroso por el orden de la condición: "agrega el atributo modelo a
 * Auto" nombra un atributo y pasa de largo.
 *
 * <p>"Diagrama", en cambio, sólo aparece en frases completas ("diagrama completo", "todo el
 * diagrama"). Suelto se llevaba puesto a "hacé que el diagrama quede más lindo", que no es un
 * pedido de dominio sino un comando que no sabemos hacer: contestarle con el rechazo de RF-02.6
 * sería responderle a algo que no preguntó. Cada palabra de esta lista se gana el lugar
 * mostrando el ejemplo del documento que la necesita.
 *
 * <p>Está escrita para <b>errar hacia dejar pasar</b>. Un falso positivo le niega al usuario un
 * comando perfectamente válido y no le da ninguna salida; un falso negativo simplemente sigue de
 * largo y cae en las comprobaciones duras, que son las que no se pueden esquivar.
 */
@Component
public class CommandGuardrail {

    /** El texto exacto que manda RF-02.6. Es la frase que va a escuchar el tribunal. */
    public static final String RECHAZO =
            "Soy un asistente de edición puntual, no genero dominios completos desde cero. "
                    + "Por favor indícame clases, atributos o relaciones específicas.";

    /** Pide un contenedor: algo que se compone de muchas piezas, no una pieza. */
    private static final Pattern CONTENEDOR = Pattern.compile(
            "\\b(sistema|sistemas|dominio|dominios|aplicacion|aplicaciones|app|apps|plataforma"
                    + "|software|erp|crm|modelo|modelos|base de datos|bases de datos|backend"
                    + "|diagrama completo|todo el diagrama|proyecto entero)\\b");

    /** Nombra una pieza concreta del metamodelo: es lo que hace un comando de edición puntual. */
    private static final Pattern ELEMENTO = Pattern.compile(
            "\\b(clase|clases|atributo|atributos|campo|campos|metodo|metodos|operacion"
                    + "|relacion|relaciones|asociacion|agregacion|composicion|herencia|hereda"
                    + "|dependencia|paquete|paquetes|entidad|entidades|tabla|tablas)\\b");

    /**
     * Si el comando pide generar un dominio entero.
     *
     * <p>Ojo con el orden de la condición: <b>mencionar una pieza gana siempre</b>. "Agrega la
     * clase Cuenta al sistema bancario" nombra un contenedor y también una clase, y es un
     * comando legítimo — la palabra "sistema" ahí es contexto, no el pedido.
     */
    public boolean pideUnDominioEntero(String comando) {
        if (comando == null || comando.isBlank()) {
            return false;
        }
        String texto = normalizar(comando);
        return CONTENEDOR.matcher(texto).find() && !ELEMENTO.matcher(texto).find();
    }

    /**
     * Minúsculas y sin tildes.
     *
     * <p>Hace falta de verdad y no es prolijidad: el comando puede llegar de un dictado por voz,
     * y el reconocimiento del navegador escribe "aplicación" con tilde mientras que el usuario
     * que teclea apurado escribe "aplicacion" sin ella. Un guardarraíl que se saltea con una
     * tilde no es un guardarraíl.
     */
    private static String normalizar(String texto) {
        String minusculas = texto.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(minusculas, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
