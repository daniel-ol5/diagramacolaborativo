package com.modelcollab.ai.service;

import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Auto-layout por grilla determinista (RF-02.5): celdas de 340 × 260 px.
 *
 * <h2>Por qué hace falta</h2>
 *
 * <p>Una clase creada por comando no tiene dónde ir: el usuario dijo "crea la clase Factura" y no
 * dijo ninguna coordenada. Sin motor de disposición, lo único que queda es ponerla en un lugar
 * fijo — y entonces <b>la segunda clase creada por comando aparece exactamente encima de la
 * primera</b>, que es lo que hace que la función parezca rota aunque el modelo esté bien.
 *
 * <h2>Determinista, y por qué importa</h2>
 *
 * <p>Nada de coordenadas al azar. El mismo diagrama con la misma cantidad de clases nuevas
 * produce siempre las mismas posiciones, y eso es lo que permite tener un test que afirme dónde
 * quedó la clase. Con posiciones aleatorias, lo único que se puede comprobar es que "no se
 * superponen", que es una propiedad mucho más débil y que se cumple por casualidad muy seguido.
 *
 * <p>La ocupación se calcula por <b>rectángulos</b> y no por posiciones: una clase que el usuario
 * arrastró a mano y quedó a caballo entre cuatro celdas las ocupa a las cuatro. Mirar sólo la
 * esquina superior izquierda daría celdas "libres" que en pantalla están tapadas.
 */
@Component
public class GridLayoutEngine {

    /** Celda de RF-02.5. Da lugar a una clase de 240 × 180 más el espacio para los conectores. */
    public static final double ANCHO_DE_CELDA = 340d;

    public static final double ALTO_DE_CELDA = 260d;

    /** Margen dentro de la celda: separa las cajas en vez de dejarlas pegadas borde con borde. */
    private static final double MARGEN = 20d;

    /** Ancho de la grilla antes de bajar de fila. Cuatro columnas entran en una pantalla. */
    private static final int COLUMNAS = 4;

    /** Tope de búsqueda. Con 4 columnas son 400 celdas: más que cualquier diagrama razonable. */
    private static final int FILAS_MAXIMAS = 100;

    /** Hacia dónde mueve un comando de voz (RF-02.3: "mueve la clase Cliente a la derecha"). */
    public enum Direccion {
        LEFT(-1, 0),
        RIGHT(1, 0),
        UP(0, -1),
        DOWN(0, 1);

        private final int dx;
        private final int dy;

        Direccion(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    /**
     * La primera celda libre, en orden de lectura.
     *
     * @param ocupadasAdemas celdas tomadas por clases que este mismo comando va a crear y que
     *     todavía no están en el modelo. Sin esto, un comando que crea dos clases las pone a las
     *     dos en la misma celda.
     */
    public Position siguienteHueco(CanonicalModel modelo, Set<String> ocupadasAdemas) {
        Set<String> ocupadas = celdasOcupadas(modelo);
        ocupadas.addAll(ocupadasAdemas);

        for (int fila = 0; fila < FILAS_MAXIMAS; fila++) {
            for (int columna = 0; columna < COLUMNAS; columna++) {
                if (!ocupadas.contains(clave(columna, fila))) {
                    return origenDe(columna, fila);
                }
            }
        }
        // Diagrama absurdamente grande: se apila al final en vez de fallar. Un comando que no
        // se aplica porque "no hay lugar en la grilla" sería una explicación imposible de dar.
        return origenDe(0, FILAS_MAXIMAS);
    }

    /** La celda que le toca a una posición, como clave para el conjunto de ocupadas. */
    public String celdaDe(Position posicion) {
        return clave(columnaDe(posicion.x()), filaDe(posicion.y()));
    }

    /**
     * Un paso en la dirección pedida, saltando lo que esté ocupado.
     *
     * <p>Si la clase ya está contra el borde —columna 0 y le piden ir a la izquierda— <b>se queda
     * donde está</b>. Es deliberado: mandarla a "la primera celda libre" la teletransportaría al
     * otro extremo del lienzo, que no se parece en nada a lo que pidió el usuario. Quedarse
     * quieto es el resultado correcto de "movete a la izquierda" cuando no hay más izquierda, y
     * quien llama se encarga de decírselo al usuario con todas las letras.
     */
    public Position mover(CanonicalModel modelo, ClassEntity clase, Direccion direccion) {
        Set<String> ocupadas = celdasOcupadas(modelo);
        Position actual = clase.position() == null ? new Position(0, 0) : clase.position();
        int columna = columnaDe(actual.x());
        int fila = filaDe(actual.y());
        // La celda propia no cuenta como ocupada: la está ocupando ella misma.
        ocupadas.remove(clave(columna, fila));

        for (int paso = 1; paso <= COLUMNAS + 2; paso++) {
            int nuevaColumna = columna + direccion.dx * paso;
            int nuevaFila = fila + direccion.dy * paso;
            if (nuevaColumna < 0 || nuevaFila < 0) {
                break;
            }
            if (!ocupadas.contains(clave(nuevaColumna, nuevaFila))) {
                return origenDe(nuevaColumna, nuevaFila);
            }
        }
        return actual;
    }

    // ------------------------------------------------------------ utilitarios

    /** Toda celda que toque el rectángulo de alguna clase. */
    private Set<String> celdasOcupadas(CanonicalModel modelo) {
        Set<String> ocupadas = new HashSet<>();
        for (ClassEntity clase : modelo.classes()) {
            Position p = clase.position() == null ? new Position(0, 0) : clase.position();
            double ancho = clase.width() == null ? ClassEntity.DEFAULT_WIDTH : clase.width();
            double alto = clase.height() == null ? ClassEntity.DEFAULT_HEIGHT : clase.height();

            int desdeColumna = columnaDe(p.x());
            int hastaColumna = columnaDe(p.x() + Math.max(0, ancho - 1));
            int desdeFila = filaDe(p.y());
            int hastaFila = filaDe(p.y() + Math.max(0, alto - 1));

            for (int c = desdeColumna; c <= hastaColumna; c++) {
                for (int f = desdeFila; f <= hastaFila; f++) {
                    ocupadas.add(clave(c, f));
                }
            }
        }
        return ocupadas;
    }

    private static Position origenDe(int columna, int fila) {
        return new Position(columna * ANCHO_DE_CELDA + MARGEN, fila * ALTO_DE_CELDA + MARGEN);
    }

    private static int columnaDe(double x) {
        return Math.max(0, (int) Math.floor(x / ANCHO_DE_CELDA));
    }

    private static int filaDe(double y) {
        return Math.max(0, (int) Math.floor(y / ALTO_DE_CELDA));
    }

    private static String clave(int columna, int fila) {
        return columna + ":" + fila;
    }
}
