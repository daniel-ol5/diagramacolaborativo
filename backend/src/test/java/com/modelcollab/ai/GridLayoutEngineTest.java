package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Auto-layout por grilla de RF-02.5 (celdas de 340 × 260).
 *
 * <p>Sin esto, la segunda clase creada por comando aparece encima de la primera y la función
 * parece rota aunque el modelo esté perfecto. Es de esos detalles que no son de modelado pero
 * deciden si alguien confía en la herramienta.
 */
class GridLayoutEngineTest {

    private final GridLayoutEngine grilla = new GridLayoutEngine();

    private static CanonicalModel con(ClassEntity... clases) {
        return new CanonicalModel("1.0.0", 1L, List.of(), List.of(clases), List.of());
    }

    private static ClassEntity clase(String nombre, double x, double y) {
        return new ClassEntity(nombre, nombre, null, false, new Position(x, y),
                null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("En un diagrama vacío la primera clase va a la primera celda")
    void primeraCelda() {
        Position p = grilla.siguienteHueco(con(), Set.of());

        assertThat(p.x()).isEqualTo(20d);
        assertThat(p.y()).isEqualTo(20d);
    }

    @Test
    @DisplayName("Diez clases seguidas no se superponen NI UNA vez")
    void diezClasesSinSuperponerse() {
        CanonicalModel modelo = con();
        Set<String> reservadas = new HashSet<>();
        List<Position> puestas = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Position p = grilla.siguienteHueco(modelo, reservadas);
            reservadas.add(grilla.celdaDe(p));
            puestas.add(p);
        }

        // Que no se repita la POSICIÓN no alcanza: dos cajas pueden estar en coordenadas
        // distintas y taparse igual. Lo que hay que comprobar es que no se solapan los
        // rectángulos, que es lo que se ve en pantalla.
        for (int i = 0; i < puestas.size(); i++) {
            for (int j = i + 1; j < puestas.size(); j++) {
                assertThat(seSuperponen(puestas.get(i), puestas.get(j)))
                        .as("la caja %d y la %d se tapan: %s y %s",
                                i, j, puestas.get(i), puestas.get(j))
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("La misma entrada da siempre la misma salida")
    void esDeterminista() {
        // Con posiciones al azar lo único comprobable sería "no se superponen", que se cumple
        // por casualidad muy seguido. Determinista, el test puede afirmar dónde quedó.
        CanonicalModel modelo = con(clase("A", 20, 20));

        assertThat(grilla.siguienteHueco(modelo, Set.of()))
                .isEqualTo(grilla.siguienteHueco(modelo, Set.of()))
                .isEqualTo(new Position(360d, 20d));
    }

    @Test
    @DisplayName("Una clase arrastrada a mano entre cuatro celdas las ocupa a las cuatro")
    void unaClaseACaballoOcupaTodoLoQueToca() {
        // Mirar sólo la esquina superior izquierda daría por libre una celda que en pantalla
        // está tapada por la mitad de una caja.
        ClassEntity aCaballo = clase("Arrastrada", 330, 250);

        Position hueco = grilla.siguienteHueco(con(aCaballo), Set.of());

        assertThat(seSuperponen(hueco, new Position(330, 250)))
                .as("la clase nueva cayó encima de la que estaba a caballo")
                .isFalse();
    }

    @Test
    @DisplayName("Mover a la derecha corre una celda")
    void moverALaDerecha() {
        ClassEntity c = clase("Cliente", 20, 20);

        Position destino = grilla.mover(con(c), c, GridLayoutEngine.Direccion.RIGHT);

        assertThat(destino).isEqualTo(new Position(360d, 20d));
    }

    @Test
    @DisplayName("Mover salta la celda ocupada en vez de apilar")
    void moverSaltaLoOcupado() {
        ClassEntity c = clase("Cliente", 20, 20);
        ClassEntity estorbo = clase("Estorbo", 360, 20);

        Position destino = grilla.mover(con(c, estorbo), c, GridLayoutEngine.Direccion.RIGHT);

        assertThat(destino).isEqualTo(new Position(700d, 20d));
    }

    @Test
    @DisplayName("Contra el borde se queda quieta en vez de teletransportarse")
    void contraElBordeNoSeMueve() {
        // Mandarla "a la primera celda libre" la llevaría al otro extremo del lienzo, que no se
        // parece en nada a lo que pidió el usuario. Quedarse es el resultado correcto, y quien
        // llama se encarga de decírselo.
        ClassEntity c = clase("Cliente", 20, 20);

        assertThat(grilla.mover(con(c), c, GridLayoutEngine.Direccion.LEFT))
                .isEqualTo(new Position(20d, 20d));
        assertThat(grilla.mover(con(c), c, GridLayoutEngine.Direccion.UP))
                .isEqualTo(new Position(20d, 20d));
    }

    private static boolean seSuperponen(Position a, Position b) {
        double ancho = ClassEntity.DEFAULT_WIDTH;
        double alto = ClassEntity.DEFAULT_HEIGHT;
        return a.x() < b.x() + ancho && b.x() < a.x() + ancho
                && a.y() < b.y() + alto && b.y() < a.y() + alto;
    }
}
