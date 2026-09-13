package com.modelcollab.collaboration.dto;

import java.util.UUID;

/**
 * Quién es un colaborador a los ojos de la sala. Se arma una vez en el CONNECT y viaja en los
 * atributos de la sesión WebSocket: los locks y la presencia lo necesitan en cada mensaje, y
 * volver a la base por el nombre en cada uno sería el mismo error que cachear el rol resolvió.
 *
 * <p>El campo se llama {@code userName} y no {@code nombre} (desvío <b>D-24</b>) porque
 * <b>todo el contrato de cable está en inglés</b>: §7 usa {@code schemaVersion} e
 * {@code isAbstract}, D-11 usa {@code op} y {@code clientMutationId}, y las difusiones de cursor
 * y de lock ya traían {@code userName} para este mismo dato. Un único campo en español entre
 * veinte en inglés es el que produce el {@code participante.userName} a las dos de la mañana, y
 * es un error que el compilador de TypeScript no puede prevenir del otro lado del cable. El
 * español se queda donde sirve: comentarios, mensajes al usuario y documentación.
 */
public record Participante(UUID userId, String userName, String color) {

    /**
     * Paleta fija. El color se deriva del {@code userId} y <b>no</b> se reparte por orden de
     * llegada: así el mismo usuario tiene el mismo color en todas las pestañas, en todas las
     * salas y después de reconectar. Con asignación por orden, quien se reconecta cambia de
     * color y los demás ven "otra persona" editando.
     *
     * <h2>Por qué estos ocho y no otros</h2>
     *
     * <p>Estos colores son el borde del candado de edición y el trazo del cursor ajeno. O sea:
     * <b>son señal, no decoración</b>. Un color que no se ve, o que se confunde con el de otro
     * colaborador, no es un detalle estético — es exclusión mutua que deja de comunicarse.
     *
     * <p>Cada uno cumple tres condiciones <b>medidas</b>, no estimadas a ojo
     * ({@code ParticipantePaletaTest} las comprueba en cada compilación):
     *
     * <ol>
     *   <li><b>Contraste ≥ 3:1 sobre blanco.</b> Es el umbral de WCAG 2.1 para objetos
     *       gráficos, que es lo que son: un borde y un trazo, no texto. El mínimo de esta
     *       paleta es 3,06:1.</li>
     *   <li><b>Separables bajo dicromacia.</b> Toda pareja está a ΔE ≥ 15 en CIE Lab, y no sólo
     *       con visión normal: también con la protanopia y la deuteranopia simuladas
     *       (Viénot et al., 1999). Cerca del 8 % de los varones tiene alguna de las dos, así
     *       que en un tribunal de cinco personas no es una hipótesis remota.</li>
     *   <li><b>Lejos de la tinta del lienzo.</b> ΔE ≥ 25 respecto del negro y del gris oscuro.
     *       Esta condición no la exige ninguna norma y la agregamos igual: un cursor del color
     *       del trazo del propio diagrama se distingue perfecto de los otros siete colores y
     *       no se ve igual. La métrica de separación entre colores no puede detectar eso
     *       porque sólo mira la paleta contra sí misma.</li>
     * </ol>
     *
     * <p>La paleta anterior fallaba las tres. <b>Cuatro de sus diez colores</b> no llegaban a
     * 3:1 —el lima {@code #bfef45} daba 1,34:1, o sea prácticamente invisible—; el rojo y el
     * verde quedaban a ΔE 9,2 bajo deuteranopia; y el par peor de todos no era ése sino el azul
     * {@code #4363d8} contra el magenta {@code #f032e6}, que bajo protanopia colapsan a ΔE 6,1.
     *
     * <p><b>Son ocho y no diez a propósito.</b> Con estos candidatos, diez colores bajan el peor
     * par a ΔE 10,7 y nueve a 14,1. Sumar colores para reducir las coincidencias tiene poco
     * rendimiento —con diez y cuatro colaboradores la probabilidad de que dos compartan color
     * sigue siendo cercana a la mitad, por el problema del cumpleaños— mientras que el costo en
     * separación es inmediato y lo paga todo el mundo. Es preferible que dos personas compartan
     * color de vez en cuando, con el nombre al lado para desempatar, a que dos colores distintos
     * parezcan el mismo.
     */
    private static final String[] PALETA = {
        "#D55E00", "#006CD1", "#1B9E77", "#5D3A9B",
        "#D41159", "#A65628", "#7570B3", "#CC79A7"
    };

    public static Participante de(UUID userId, String userName) {
        int indice = Math.floorMod(userId.hashCode(), PALETA.length);
        return new Participante(userId, userName, PALETA[indice]);
    }
}
