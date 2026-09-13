package com.modelcollab.ai.service;

/**
 * El servicio de IA rompió el contrato de salida, o no contestó.
 *
 * <p>Un modelo de lenguaje puede devolver cualquier cosa: texto que no es JSON, un campo que el
 * esquema no define, una operación que no está en el catálogo de §8.1, un tipo de atributo
 * inventado. Nada de eso puede entrar al modelo canónico — <b>el canónico es lo que sostiene el
 * lienzo, el generador y el exportador</b>, y una clase con un tipo que no existe se convierte
 * más tarde en un ZIP que no compila, lejos de acá y sin rastro de quién lo puso.
 *
 * <p>Se traduce a <b>502</b>: el que falló es el servicio de arriba, no el usuario, y así
 * aparece como lo que es en cualquier panel de monitoreo. El mensaje igual está escrito para que
 * se pueda mostrar en pantalla, porque el usuario merece enterarse de que no fue culpa suya.
 */
public class RespuestaDeIaInvalidaException extends RuntimeException {

    private final String motivo;

    public RespuestaDeIaInvalidaException(String motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public RespuestaDeIaInvalidaException(String motivo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.motivo = motivo;
    }

    public String getMotivo() {
        return motivo;
    }
}
