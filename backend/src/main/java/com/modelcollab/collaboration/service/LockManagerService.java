package com.modelcollab.collaboration.service;

import com.modelcollab.collaboration.dto.LockBroadcastMessage;
import com.modelcollab.collaboration.dto.LockDeniedMessage;
import com.modelcollab.collaboration.dto.Participante;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Exclusión mutua por soft-locks con TTL (§8.2). Es el árbitro que impide que dos personas
 * renombren la misma clase a la vez.
 *
 * <h2>Por qué en memoria y no en PostgreSQL</h2>
 *
 * <p>Un lock dura 5 segundos. Persistirlo haría que <b>sobreviviera a un reinicio del
 * servidor</b>, dejando elementos bloqueados por un usuario que ya no está conectado y sin nadie
 * que los libere: sería un defecto disfrazado de durabilidad (§8.2 punto 6). El estado efímero es
 * la propiedad correcta, no una limitación. Para el escalado horizontal de §5.3 este mapa se
 * muda al mismo Redis del relay STOMP con {@code SET NX PX 5000}, sin tocar el protocolo del
 * cliente.
 *
 * <h2>Fallo rápido, sin cola</h2>
 *
 * <p>Si el recurso está tomado se responde {@code LOCK_DENIED} en el acto. Nadie espera, nadie
 * hace cola. <b>Esa es la razón por la que el sistema no puede tener interbloqueos ni
 * inanición</b>: un cliente jamás queda esperando a otro, así que no hay ciclo de espera posible
 * — la condición de Coffman que el diseño rompe deliberadamente.
 */
@Service
public class LockManagerService {

    private static final Logger log = LoggerFactory.getLogger(LockManagerService.class);

    /** TTL de §8.2: 5 segundos. El cliente lo renueva con un heartbeat cada 2000 ms. */
    public static final long TTL_MS = 5000L;

    /** Un lock vivo. {@code sessionId} es lo que permite soltarlo todo al desconectar. */
    public record LockEntry(
            UUID diagramId,
            String targetId,
            Participante duenio,
            String sessionId,
            long expiraEnMs) {}

    /** Resultado de pedir un lock: o se concede, o se deniega con el motivo. */
    public sealed interface Resultado {
        record Concedido(LockEntry lock, boolean renovacion) implements Resultado {}

        record Denegado(String heldBy, long expiresInMs) implements Resultado {}
    }

    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock reloj;

    /**
     * @Autowired explicito porque hay dos constructores publicos: sin la anotacion Spring no
     * sabe cual usar, busca uno sin argumentos, no lo encuentra y la aplicacion no arranca.
     */
    @Autowired
    public LockManagerService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, Clock.systemUTC());
    }

    /**
     * Constructor con reloj inyectable. Existe para los tests: probar un TTL de 5 segundos con
     * {@code Thread.sleep} haria que la suite tardara medio minuto y que fallara de a ratos en
     * una maquina cargada, y un test de expiracion intermitente es peor que no tenerlo.
     */
    public LockManagerService(SimpMessagingTemplate messagingTemplate, Clock reloj) {
        this.messagingTemplate = messagingTemplate;
        this.reloj = reloj;
    }

    /**
     * Pide (o renueva) el lock de un elemento y comunica el resultado según §8.2: la concesión se
     * difunde a la sala, la denegación va sólo al solicitante.
     */
    public Resultado adquirir(
            UUID diagramId, String targetId, Participante quien, String sessionId) {

        String clave = clave(diagramId, targetId);
        long ahora = reloj.millis();

        // Se anota dentro del compute si el lock ya era de este usuario: consultarlo después
        // sería inútil, porque para entonces el mapa ya tiene la entrada nueva.
        boolean[] yaEraSuyo = {false};

        // compute() es atómico sobre la clave: dos peticiones simultáneas por el mismo elemento
        // se serializan acá dentro. Sin esto, dos hilos podrían ver el mapa libre a la vez y
        // concederle el lock a los dos, que es justamente el fallo que este servicio existe
        // para impedir.
        LockEntry resultado = locks.compute(clave, (k, actual) -> {
            boolean vigente = actual != null && actual.expiraEnMs() > ahora;
            if (vigente && !actual.duenio().userId().equals(quien.userId())) {
                return actual; // tomado por otro: se conserva y más abajo se deniega
            }
            yaEraSuyo[0] = vigente;
            return new LockEntry(diagramId, targetId, quien, sessionId, ahora + TTL_MS);
        });

        if (!resultado.duenio().userId().equals(quien.userId())) {
            long restante = Math.max(0, resultado.expiraEnMs() - ahora);
            messagingTemplate.convertAndSendToUser(
                    quien.userId().toString(),
                    "/queue/locks",
                    LockDeniedMessage.de(targetId, resultado.duenio().nombre(), restante));
            log.debug("LOCK_DENIED {} para {}: lo tiene {}",
                    targetId, quien.nombre(), resultado.duenio().nombre());
            return new Resultado.Denegado(resultado.duenio().nombre(), restante);
        }

        // Una renovación por heartbeat no se vuelve a difundir: la sala ya pintó el candado y
        // reenviarlo cada 2 segundos por usuario sería ruido puro sobre el canal.
        if (!yaEraSuyo[0]) {
            difundir(diagramId, LockBroadcastMessage.adquirido(targetId, quien, TTL_MS));
            log.debug("LOCK_ACQUIRED {} para {}", targetId, quien.nombre());
        }
        return new Resultado.Concedido(resultado, yaEraSuyo[0]);
    }

    /**
     * Libera explícitamente. Sólo el dueño puede: pedir la liberación de un lock ajeno no hace
     * nada, porque si no cualquiera podría destrabar el elemento que otro está editando y la
     * exclusión mutua sería decorativa.
     */
    public boolean liberar(UUID diagramId, String targetId, UUID userId) {
        String clave = clave(diagramId, targetId);
        LockEntry actual = locks.get(clave);
        if (actual == null || !actual.duenio().userId().equals(userId)) {
            return false;
        }
        // remove(clave, valor) compara antes de borrar: si el barrido de vencidos se adelantó y
        // otro usuario ya tomó el lock en el intervalo, no se lo pisa.
        if (locks.remove(clave, actual)) {
            difundir(diagramId, LockBroadcastMessage.liberado(targetId));
            return true;
        }
        return false;
    }

    /**
     * Si el usuario posee el lock <b>en este instante</b>. Lo consulta el servicio de mutación
     * antes de aplicar una operación de las que §8.1 marca "Requiere Lock".
     */
    public boolean poseeLock(UUID diagramId, String targetId, UUID userId) {
        LockEntry actual = locks.get(clave(diagramId, targetId));
        return actual != null
                && actual.expiraEnMs() > reloj.millis()
                && actual.duenio().userId().equals(userId);
    }

    /**
     * Suelta todo lo que tenía una sesión que se fue. Sin esto, cerrar la pestaña deja el
     * elemento bloqueado hasta que venza el TTL y, peor, deja al resto de la sala mirando un
     * candado de alguien que ya no está.
     */
    public int liberarTodosDeLaSesion(String sessionId) {
        return retirar(entrada -> entrada.sessionId().equals(sessionId), "desconexión");
    }

    /**
     * Barrido único de vencidos. <b>Uno solo para todos los locks, no un temporizador por
     * lock:</b> con veinte personas editando habría decenas de {@code ScheduledFuture} vivos,
     * cada uno con su cancelación y su reprogramación en cada heartbeat. Un barrido de un mapa
     * pequeño una vez por segundo es más barato y muchísimo más simple de razonar.
     *
     * <p>Hace falta además de la comprobación perezosa de {@link #poseeLock}: al vencer hay que
     * <b>emitir {@code LOCK_RELEASED}</b> para que los demás clientes saquen el candado de la
     * pantalla, y eso nadie lo dispara si nadie pregunta.
     */
    @Scheduled(fixedRate = 1000)
    public void barrerVencidos() {
        long ahora = reloj.millis();
        retirar(entrada -> entrada.expiraEnMs() <= ahora, "TTL vencido");
    }

    private int retirar(java.util.function.Predicate<LockEntry> criterio, String motivo) {
        List<LockEntry> retirados = new ArrayList<>();
        for (Iterator<Map.Entry<String, LockEntry>> it = locks.entrySet().iterator();
                it.hasNext(); ) {
            LockEntry entrada = it.next().getValue();
            if (criterio.test(entrada)) {
                it.remove();
                retirados.add(entrada);
            }
        }
        for (LockEntry entrada : retirados) {
            log.debug("LOCK_RELEASED {} por {}", entrada.targetId(), motivo);
            difundir(entrada.diagramId(), LockBroadcastMessage.liberado(entrada.targetId()));
        }
        return retirados.size();
    }

    private void difundir(UUID diagramId, LockBroadcastMessage mensaje) {
        messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId, mensaje);
    }

    private static String clave(UUID diagramId, String targetId) {
        return diagramId + "|" + targetId;
    }

    /** Cantidad de locks vivos. Sólo para tests y diagnóstico. */
    public int cantidadDeLocks() {
        return locks.size();
    }
}
