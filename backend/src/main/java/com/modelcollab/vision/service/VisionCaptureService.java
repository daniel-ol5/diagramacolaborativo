package com.modelcollab.vision.service;

import com.modelcollab.collaboration.dto.Participante;
import com.modelcollab.collaboration.service.RoomManager;
import com.modelcollab.vision.dto.VisionCaptureBroadcast;
import com.modelcollab.vision.dto.VisionCaptureResponse;
import com.modelcollab.vision.dto.VisionProposalResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Una foto sacada desde el celular termina en el modal de revisión de la computadora.
 *
 * <h2>No hay un camino de visión nuevo</h2>
 *
 * <p>El análisis es <b>el mismo de CU17, sin tocar nada</b>: el mismo validador de imagen, el
 * mismo adaptador, el mismo fusionador blando. Lo único que cambia es por dónde entra la foto y a
 * dónde va la propuesta. Si esto tuviera su propia validación o su propio cliente, tendríamos dos
 * caminos que se irían separando — y el que se usa menos sería el que se rompe sin que nadie se
 * entere.
 *
 * <h2>Sin tabla y sin almacenamiento</h2>
 *
 * <p>La propuesta existe mientras viaja. No se guarda: guardarla obligaría a decidir cuándo
 * caduca, quién la borra y qué pasa si el diagrama cambió mientras tanto, y todo eso para un dato
 * que sólo sirve durante los diez segundos que la persona tarda en mirar la pantalla. La foto
 * tampoco se guarda, por lo mismo que en CU17: son fotos de pizarras ajenas y lo que no se
 * guarda no se filtra.
 */
@Service
public class VisionCaptureService {

    private static final Logger log = LoggerFactory.getLogger(VisionCaptureService.class);

    /** El canal privado de quien sacó la foto. Deliberadamente NO es el topic del diagrama. */
    public static final String COLA_VISION = "/queue/vision";

    private final VisionImportService importService;
    private final RoomManager roomManager;
    private final SimpMessagingTemplate messagingTemplate;

    public VisionCaptureService(
            VisionImportService importService,
            RoomManager roomManager,
            SimpMessagingTemplate messagingTemplate) {
        this.importService = importService;
        this.roomManager = roomManager;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Analiza la foto y se la empuja al navegador de quien la sacó.
     *
     * <p><b>Se pregunta primero si hay alguien mirando, y recién después se analiza.</b> El orden
     * importa por dos motivos: leer una foto cuesta una llamada a un servicio que se paga, y sin
     * una pantalla escuchando esa lectura no tiene a dónde ir — no se guarda. Analizar primero
     * sería gastar la llamada para tirar el resultado.
     *
     * <p>La pregunta es <b>por diagrama y por usuario</b>, no "¿hay alguien conectado?". Que un
     * compañero tenga el diagrama abierto no sirve: la propuesta va al canal privado de quien
     * sacó la foto, y si esa persona no lo tiene abierto, el mensaje se emite contra un canal que
     * nadie escucha y se pierde en silencio. Eso es lo peor que puede pasar acá, porque desde el
     * teléfono se vería idéntico a un éxito.
     */
    public VisionCaptureResponse recibir(UUID diagramId, UUID userId, byte[] imagen) {
        if (!estaMirando(diagramId, userId)) {
            log.debug("Foto desde el celular para {} sin pantalla escuchando de {}",
                    diagramId, userId);
            return VisionCaptureResponse.sinPantalla();
        }

        VisionProposalResponse propuesta = importService.analizar(diagramId, imagen);

        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                COLA_VISION,
                VisionCaptureBroadcast.delCelular(diagramId, propuesta));

        log.debug("Foto desde el celular empujada a {} en {}: {} clase(s)",
                userId, diagramId, propuesta.draft().classes().size());
        return VisionCaptureResponse.entregada(propuesta);
    }

    /** Si ESTE usuario tiene ESTE diagrama abierto en alguna pestaña. */
    private boolean estaMirando(UUID diagramId, UUID userId) {
        return roomManager.participantes(diagramId).stream()
                .map(Participante::userId)
                .anyMatch(userId::equals);
    }
}
