package com.modelcollab.collaboration.service;

/**
 * Una mutación que el servidor no puede aplicar. Lleva un {@code code} simbólico porque el
 * cliente tiene que poder ramificar sin parsear el mensaje: un {@code LOCK_REQUIRED} se resuelve
 * pidiendo el lock y reintentando, mientras que un {@code TARGET_NOT_FOUND} significa que el
 * cliente tiene una copia vieja del diagrama y lo que corresponde es recargar el snapshot.
 */
public class MutacionRechazadaException extends RuntimeException {

    public static final String OP_DESCONOCIDA = "UNKNOWN_OP";
    public static final String OP_NO_IMPLEMENTADA = "OP_NOT_IMPLEMENTED";
    public static final String OBJETIVO_INEXISTENTE = "TARGET_NOT_FOUND";
    public static final String PAYLOAD_INVALIDO = "INVALID_PAYLOAD";
    public static final String ID_DUPLICADO = "DUPLICATE_ID";
    public static final String LOCK_REQUERIDO = "LOCK_REQUIRED";

    private final String code;
    private final int status;

    public MutacionRechazadaException(String code, int status, String mensaje) {
        super(mensaje);
        this.code = code;
        this.status = status;
    }

    public static MutacionRechazadaException objetivoInexistente(String que, String id) {
        return new MutacionRechazadaException(
                OBJETIVO_INEXISTENTE, 404, que + " " + id + " no existe en el diagrama");
    }

    public static MutacionRechazadaException payloadInvalido(String mensaje) {
        return new MutacionRechazadaException(PAYLOAD_INVALIDO, 400, mensaje);
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }
}
