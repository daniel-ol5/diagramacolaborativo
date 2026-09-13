package com.modelcollab.collaboration.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Número de secuencia monótono por diagrama. Es el reloj lógico de la colaboración: el orden
 * total que define qué versión del diagrama es más nueva y sobre el que se apoya el rebase
 * offline del Día 10.
 *
 * <h2>Por qué el incremento lo hace PostgreSQL y no Java</h2>
 *
 * <p>La versión ingenua —leer {@code mutation_version}, sumarle uno y guardar— produce números
 * repetidos apenas hay concurrencia: dos transacciones leen 7, las dos escriben 8, y se pierde
 * una operación del historial. Un {@code synchronized} lo taparía en un solo proceso pero deja
 * de valer en cuanto haya dos instancias detrás del balanceador de §5.3.
 *
 * <p>{@code UPDATE ... SET n = n + 1 ... RETURNING n} lo resuelve en el motor: PostgreSQL toma
 * el lock de fila, y la segunda transacción espera y lee el valor ya incrementado. El lock se
 * suelta al commit, lo que además <b>serializa toda la mutación</b>, no sólo el contador: dos
 * usuarios que muten el mismo diagrama a la vez se aplican uno después del otro sobre estados
 * consistentes, y nunca sobre la misma copia leída dos veces.
 *
 * <p>Va por JDBC directo (a través de {@code doReturningWork}, que usa la conexión de la
 * transacción en curso) porque {@code UPDATE ... RETURNING} es una sentencia que devuelve filas,
 * un híbrido que las APIs de consulta de JPA no modelan.
 */
@Service
public class SequenceService {

    private static final String INCREMENTO_ATOMICO =
            "UPDATE diagrams SET mutation_version = mutation_version + ? "
                    + "WHERE id = ? RETURNING mutation_version";

    @PersistenceContext private EntityManager entityManager;

    /**
     * Incrementa y devuelve la nueva versión del diagrama.
     *
     * <p>{@code MANDATORY}: obliga a que exista ya una transacción del llamador. Si se pudiera
     * invocar suelto, el número se consumiría y se commitearía por su cuenta, y una mutación que
     * después falla dejaría un hueco permanente en la secuencia — justo lo que rompería al
     * cliente offline, que pide "todo lo que pasó desde la secuencia N".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long siguiente(UUID diagramId) {
        return reservar(diagramId, 1);
    }

    /**
     * Reserva {@code cantidad} numeros consecutivos de una sola vez y devuelve <b>el ultimo</b>
     * del bloque; los reservados son {@code [devuelto - cantidad + 1 .. devuelto]}.
     *
     * <p>Lo usa el rebase offline, que aplica un lote entero: pedir los numeros de a uno serian
     * N viajes a la base dentro de la misma transaccion para un resultado identico. Con un solo
     * UPDATE el bloque queda reservado de forma atomica y nadie puede intercalarse en el medio.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long reservar(UUID diagramId, int cantidad) {
        if (cantidad < 1) {
            throw new IllegalArgumentException("Hay que reservar al menos un numero");
        }
        return incrementar(diagramId, cantidad);
    }

    /**
     * Toma el lock de fila del diagrama <b>sin consumir ningun numero</b>, y devuelve la version
     * actual.
     *
     * <p>Existe por una carrera concreta. Casi todas las mutaciones describen un cambio
     * relativo ("agrega este atributo"), asi que al aplicador le alcanza con leer el estado
     * despues de tomar el lock, que es lo que ya hace. Pero un {@code BULK_MERGE} lleva en el
     * payload el <b>modelo resultante completo</b>, y ese modelo hay que calcularlo leyendo el
     * estado actual <i>antes</i> de llamar al aplicador.
     *
     * <p>Si esa lectura ocurre fuera del lock, entre ella y la aplicacion cabe la mutacion de
     * otro colaborador: el payload se calculo sobre un estado viejo y, como el BULK_MERGE
     * reemplaza, el cambio ajeno desaparece sin dejar rastro. No es un conflicto que alguien
     * pueda notar — es trabajo que se borra en silencio.
     *
     * <p>{@code SET n = n + 0} parece un no-operativo y no lo es: PostgreSQL toma igual el lock
     * de fila y lo sostiene hasta el commit. A partir de ahi la lectura del estado y la
     * aplicacion ocurren sobre el mismo diagrama quieto. No consume numero, asi que tampoco deja
     * el hueco en la secuencia que romperia al cliente offline.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long tomarElDiagrama(UUID diagramId) {
        return incrementar(diagramId, 0);
    }

    private long incrementar(UUID diagramId, int cantidad) {
        return entityManager
                .unwrap(Session.class)
                .doReturningWork(conexion -> {
                    try (PreparedStatement ps = conexion.prepareStatement(INCREMENTO_ATOMICO)) {
                        ps.setInt(1, cantidad);
                        ps.setObject(2, diagramId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new MutacionRechazadaException(
                                        MutacionRechazadaException.OBJETIVO_INEXISTENTE, 404,
                                        "El diagrama " + diagramId + " no existe");
                            }
                            return rs.getLong(1);
                        }
                    }
                });
    }
}
