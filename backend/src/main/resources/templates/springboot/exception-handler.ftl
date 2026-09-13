package ${paquete}.config;

import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce los errores a ProblemDetail (RFC 7807), que es el formato que Spring 6 usa por defecto
 * y que un cliente puede interpretar sin conocer este backend en particular.
 *
 * <p>NO editar a mano: este archivo se regenera desde el diagrama.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Un cuerpo que no pasa las validaciones responde 400 <b>diciendo qué campo falló</b>. Sin
     * esto, el cliente recibe un 400 vacío y no tiene forma de saber qué corregir.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validacion(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));

        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalle);
        problema.setTitle("Datos inválidos");
        return problema;
    }
}
