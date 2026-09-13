package com.modelcollab.ai.service;

/**
 * El comando se procesó y <b>no se aplicó</b>, por una razón que el usuario puede entender y
 * corregir: pidió un dominio entero, nombró una clase que no existe, o el elemento lo está
 * editando otro.
 *
 * <p>No es un error del sistema, así que el endpoint responde <b>200</b> con el motivo en el
 * cuerpo. Un rechazo educado es la respuesta del asistente, no una falla del protocolo: el
 * navegador lo muestra en la conversación como cualquier otra contestación.
 *
 * <p>La distinción con {@link RespuestaDeIaInvalidaException} importa para operar el sistema. Un
 * 5xx tiene que significar "algo se rompió" y aparecer como tal en cualquier panel de
 * monitoreo; si "el usuario pidió un banco entero" también fuera 5xx, la señal se ensuciaría
 * con el uso normal y dejaría de servir para detectar problemas de verdad.
 */
public class ComandoRechazadoException extends RuntimeException {

    private final String motivo;

    public ComandoRechazadoException(String motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public String getMotivo() {
        return motivo;
    }
}
