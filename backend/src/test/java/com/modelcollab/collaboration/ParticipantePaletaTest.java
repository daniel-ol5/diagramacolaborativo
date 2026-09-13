package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.collaboration.dto.Participante;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La paleta de colaboradores, comprobada con números en vez de con buen gusto.
 *
 * <h2>Por qué esto es un test y no un comentario</h2>
 *
 * <p>Un javadoc que explica los criterios no impide que dentro de dos semanas alguien cambie los
 * colores por unos más bonitos. Este archivo sí: los tres criterios son ejecutables, así que una
 * paleta que no los cumpla no compila la suite.
 *
 * <p>Los colores de acá son el borde del candado de edición y el trazo del cursor ajeno — son
 * <b>señal</b>. Un color que no se ve es exclusión mutua que dejó de comunicarse, que es
 * exactamente el fallo que el candado existe para evitar.
 *
 * <h2>Y por qué el último test es el más importante</h2>
 *
 * <p>{@link #laPaletaViejaNoHabriaPasado()} le pasa la paleta anterior a las mismas
 * comprobaciones y exige que fallen. Sin él, todo esto podría estar midiendo cualquier cosa y
 * dando verde igual: un arnés que nunca vio fallar lo que tiene que detectar no se sabe qué
 * cubre.
 */
class ParticipantePaletaTest {

    /** Umbral de WCAG 2.1 para objetos gráficos (1.4.11). No es texto: es un borde y un trazo. */
    private static final double CONTRASTE_MINIMO = 3.0;

    /** Separación mínima en CIE Lab entre dos colores cualesquiera, con cualquier visión. */
    private static final double SEPARACION_MINIMA = 15.0;

    /** Distancia mínima a la tinta del diagrama, para que el cursor no parezca un trazo más. */
    private static final double DISTANCIA_A_LA_TINTA = 25.0;

    private static final List<String> TINTA_DEL_LIENZO = List.of("#000000", "#333333");

    /** La paleta que había antes de medirla. Se conserva para el test invertido. */
    private static final List<String> PALETA_VIEJA = List.of(
            "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
            "#42d4f4", "#f032e6", "#bfef45", "#469990", "#9a6324");

    /**
     * La paleta tal como la ve el sistema.
     *
     * <p>Se descubre llamando a {@code Participante.de}, y no leyendo el array: así el test no
     * obliga a abrir la visibilidad de un detalle interno, y de paso comprueba que la derivación
     * desde el {@code userId} realmente reparte sobre todos los colores. Una paleta de ocho
     * donde la fórmula sólo alcanza cinco sería un defecto que mirar el array no revela.
     */
    private static List<String> paletaEnUso() {
        Set<String> colores = new LinkedHashSet<>();
        for (int i = 0; i < 5000; i++) {
            colores.add(Participante.de(UUID.randomUUID(), "x").color());
        }
        return new ArrayList<>(colores);
    }

    @Test
    @DisplayName("Todo color se ve sobre blanco: mínimo 3:1")
    void todosSeVen() {
        for (String color : paletaEnUso()) {
            assertThat(contrasteSobreBlanco(color))
                    .as("%s sobre fondo blanco: un candado que no se ve no cumple su función",
                            color)
                    .isGreaterThanOrEqualTo(CONTRASTE_MINIMO);
        }
    }

    @Test
    @DisplayName("Ningún par se confunde, tampoco con protanopia ni deuteranopia")
    void ningunParSeConfunde() {
        List<String> paleta = paletaEnUso();

        for (int i = 0; i < paleta.size(); i++) {
            for (int j = i + 1; j < paleta.size(); j++) {
                for (Vision vision : Vision.values()) {
                    // Si dos colaboradores caen en un par confundible, para una parte del
                    // público es la misma persona editando las dos cosas.
                    assertThat(distancia(paleta.get(i), paleta.get(j), vision))
                            .as("%s y %s son el mismo color con %s",
                                    paleta.get(i), paleta.get(j), vision)
                            .isGreaterThanOrEqualTo(SEPARACION_MINIMA);
                }
            }
        }
    }

    @Test
    @DisplayName("Ningún color se parece a la tinta del diagrama")
    void ningunoSeConfundeConElTrazo() {
        // Este criterio no lo exige ninguna norma. Lo agregamos porque la comprobación anterior
        // es estructuralmente incapaz de verlo: compara la paleta contra sí misma, así que un
        // negro que se distingue perfecto de los otros siete colores le parece impecable.
        for (String color : paletaEnUso()) {
            for (String tinta : TINTA_DEL_LIENZO) {
                assertThat(distancia(color, tinta, Vision.NORMAL))
                        .as("%s se confunde con el trazo del propio diagrama (%s)", color, tinta)
                        .isGreaterThanOrEqualTo(DISTANCIA_A_LA_TINTA);
            }
        }
    }

    @Test
    @DisplayName("El mismo usuario recibe siempre el mismo color")
    void elColorNoCambiaAlReconectar() {
        // Es el mecanismo, y no se toca: con reparto por orden de llegada, quien se reconecta
        // cambia de color y los demás ven "otra persona" editando.
        UUID usuario = UUID.randomUUID();

        assertThat(Participante.de(usuario, "Ana").color())
                .isEqualTo(Participante.de(usuario, "Ana en otra pestaña").color());
    }

    @Test
    @DisplayName("La fórmula reparte sobre TODOS los colores, no sobre unos pocos")
    void laFormulaUsaTodaLaPaleta() {
        assertThat(paletaEnUso())
                .as("un color que la derivación nunca alcanza es un color que no existe")
                .hasSize(8);
    }

    @Test
    @DisplayName("La paleta vieja NO habría pasado: el arnés detecta lo que existe para detectar")
    void laPaletaViejaNoHabriaPasado() {
        List<String> sinContraste = PALETA_VIEJA.stream()
                .filter(c -> contrasteSobreBlanco(c) < CONTRASTE_MINIMO)
                .toList();
        // Cuatro de diez, no uno. El lima #bfef45 daba 1,34:1 — prácticamente invisible, y el
        // celeste #42d4f4 1,76:1, que tampoco se ve.
        assertThat(sinContraste).hasSize(4).contains("#bfef45", "#42d4f4");

        // El par rojo/verde clásico bajo deuteranopia.
        assertThat(distancia("#e6194b", "#3cb44b", Vision.DEUTERANOPIA))
                .isLessThan(SEPARACION_MINIMA);

        // Y el peor de todos no era ése: azul contra magenta bajo protanopia estaba aún más
        // cerca. Es el que no se encuentra mirando la lista, sólo midiéndola.
        assertThat(distancia("#4363d8", "#f032e6", Vision.PROTANOPIA))
                .isLessThan(distancia("#e6194b", "#3cb44b", Vision.DEUTERANOPIA));
    }

    // ------------------------------------------------------------- la medición

    private enum Vision {
        NORMAL(new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}),
        // Viénot, Brettel y Mollon (1999), sobre RGB lineal.
        PROTANOPIA(new double[][] {
            {0.11238, 0.88762, 0.0}, {0.11238, 0.88762, 0.0}, {0.00401, -0.00401, 1.0}}),
        DEUTERANOPIA(new double[][] {
            {0.29275, 0.70725, 0.0}, {0.29275, 0.70725, 0.0}, {-0.02234, 0.02234, 1.0}});

        private final double[][] matriz;

        Vision(double[][] matriz) {
            this.matriz = matriz;
        }
    }

    private static double[] aLineal(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        double[] canales = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16) / 255.0;
            canales[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return canales;
    }

    private static double contrasteSobreBlanco(String hex) {
        double[] c = aLineal(hex);
        double luminancia = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
        return 1.05 / (luminancia + 0.05);
    }

    private static double[] simular(String hex, Vision vision) {
        double[] c = aLineal(hex);
        double[] salida = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = vision.matriz[i][0] * c[0]
                    + vision.matriz[i][1] * c[1]
                    + vision.matriz[i][2] * c[2];
            salida[i] = Math.max(0.0, Math.min(1.0, v));
        }
        return salida;
    }

    /** RGB lineal → XYZ (sRGB D65) → CIE Lab. */
    private static double[] aLab(double[] rgb) {
        double x = 0.4124 * rgb[0] + 0.3576 * rgb[1] + 0.1805 * rgb[2];
        double y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
        double z = 0.0193 * rgb[0] + 0.1192 * rgb[1] + 0.9505 * rgb[2];

        double fx = f(x / 0.95047);
        double fy = f(y);
        double fz = f(z / 1.08883);
        return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t) + (16.0 / 116.0);
    }

    private static double distancia(String a, String b, Vision vision) {
        double[] la = aLab(simular(a, vision));
        double[] lb = aLab(simular(b, vision));
        return Math.sqrt(Math.pow(la[0] - lb[0], 2)
                + Math.pow(la[1] - lb[1], 2)
                + Math.pow(la[2] - lb[2], 2));
    }
}
