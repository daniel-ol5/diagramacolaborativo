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
            "UPDATE diagrams SET mutation_version = mutation_version + 1 "
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
        return entityManager
                .unwrap(Session.class)
                .doReturningWork(conexion -> {
                    try (PreparedStatement ps = conexion.prepareStatement(INCREMENTO_ATOMICO)) {
                        ps.setObject(1, diagramId);
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
