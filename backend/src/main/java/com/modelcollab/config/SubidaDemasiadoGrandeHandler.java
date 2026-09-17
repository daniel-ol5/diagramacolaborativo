package com.modelcollab.config;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Un archivo tan grande que Spring lo corta antes de que llegue el controlador.
 *
 * <h2>Por qué está acá y no en el controlador de visión, donde estaba</h2>
 *
 * <p>Estaba escrito como {@code @ExceptionHandler} dentro de {@code VisionController}, y
 * <b>no se ejecutaba nunca</b>. Un {@code @ExceptionHandler} local atiende lo que lanzan los
 * métodos de <i>ese</i> controlador; {@link MaxUploadSizeExceededException} la lanza el
 * resolvedor de multipart <b>antes</b> de que se elija un controlador, así que no hay ninguno al
 * que atribuirla. Sólo la ve un {@code @ControllerAdvice}.
 *
 * <p>El síntoma era exactamente el que el manejador pretendía evitar: <b>413 con el cuerpo
 * vacío</b>. La persona sube una foto, no pasa nada, y no hay ningún texto que leer.
 *
 * <p>Lo encontró una verificación contra el servidor de verdad con un archivo de 11 MB. Los tests
 * no lo podían ver: no hay ninguno que levante Tomcat, y el camino que falla está en el
 * resolvedor de multipart, no en el código nuestro.
 *
 * <h2>Esto es la red, no la puerta</h2>
 *
 * <p>El límite de Spring está puesto <b>por encima</b> del que aplica {@code VisionImageValidator}
 * (10 MB contra 8 MB) justamente para que casi siempre hable el validador, que dice cuánto pesa la
 * foto y qué hacer al respecto. Esto atiende lo que ni siquiera llega hasta ahí.
 */
@RestControllerAdvice
public class SubidaDemasiadoGrandeHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> demasiadoGrande(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(
                        "reason", "INVALID_IMAGE",
                        "message", "La imagen es demasiado grande. El máximo son 8 MB: "
                                + "sacale resolución y volvé a intentar."));
    }
}
