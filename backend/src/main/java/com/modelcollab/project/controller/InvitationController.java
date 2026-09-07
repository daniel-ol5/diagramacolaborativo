package com.modelcollab.project.controller;

import com.modelcollab.project.dto.InvitationPreview;
import com.modelcollab.project.service.InvitationService;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que se hace con un enlace de invitación: mirarlo y aceptarlo.
 *
 * <p>Va aparte de {@code ProjectController} porque estas rutas se identifican por <b>token</b> y
 * no por proyecto — quien abre el enlace todavía no sabe a qué proyecto lo invitaron, que es
 * justamente lo que viene a averiguar.
 */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * "Te invitaron a X como EDITOR", sin agregar a nadie.
     *
     * <p>Es <b>público</b>: quien recibe un enlace tiene que poder ver qué es antes de decidir si
     * se registra. Y no expone nada: sin un token válido no devuelve ni el nombre del proyecto.
     */
    @GetMapping("/{token}")
    public InvitationPreview mirar(@PathVariable String token) {
        return invitationService.mirar(token);
    }

    /** Acepta el enlace. Requiere estar autenticado: hay que saber a quién se agrega. */
    @PostMapping("/{token}/accept")
    public Map<String, UUID> aceptar(@PathVariable String token, Principal emisor) {
        UUID projectId =
                invitationService.aceptar(token, UUID.fromString(emisor.getName()));
        return Map.of("projectId", projectId);
    }
}
