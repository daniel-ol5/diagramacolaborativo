package com.modelcollab.ai.dto;

import java.util.List;

/**
 * Lo que el navegador recibe después de un comando.
 *
 * <p>{@code applied} dice si el diagrama cambió. {@code reason} es {@code null} cuando cambió y,
 * si no, el código del motivo: la interfaz decide con eso si pinta un aviso o un error, sin
 * tener que leer el texto en castellano.
 *
 * <p>{@code operations} son las frases legibles de lo que se hizo, una por operación. No es
 * adorno: <b>el que dictó por voz no vio lo que escribió</b>, y necesita que el sistema le diga
 * qué entendió, o no tiene forma de enterarse de que "Factura" salió "Fractura".
 */
public record AiCommandResponse(
        boolean applied,
        String reason,
        String message,
        List<String> operations,
        Long mutationVersion) {

    /** Pidió generar un dominio entero (RF-02.6). */
    public static final String GENERAR_DOMINIO = "GENERATE_DOMAIN";
    /** Más operaciones que el tope duro: un comando que produce veinte no es un comando. */
    public static final String DEMASIADAS_OPERACIONES = "TOO_MANY_OPERATIONS";
    /** No se pudo traducir a ninguna operación del catálogo. */
    public static final String NO_ENTENDIDO = "NOT_UNDERSTOOD";
    /** Se nombró una clase que no está en el diagrama. */
    public static final String OBJETIVO_INEXISTENTE = "TARGET_NOT_FOUND";
    /** Aplicarlo dejaría el diagrama con errores bloqueantes que antes no tenía. */
    public static final String ROMPERIA_EL_DIAGRAMA = "WOULD_BREAK_DIAGRAM";
    /** Otro colaborador está editando ese elemento en este momento. */
    public static final String ELEMENTO_BLOQUEADO = "ELEMENT_LOCKED";
    /** El mismo clientCommandId ya se había aplicado. */
    public static final String YA_APLICADO = "ALREADY_APPLIED";
    /** El servicio de IA devolvió algo que viola el contrato de salida. */
    public static final String CONTRATO_ROTO = "AI_CONTRACT_VIOLATION";
    /** El servicio de IA no respondió. */
    public static final String IA_CAIDA = "AI_UNAVAILABLE";

    public static AiCommandResponse aplicada(
            List<String> operaciones, String mensaje, long mutationVersion) {
        return new AiCommandResponse(true, null, mensaje, operaciones, mutationVersion);
    }

    public static AiCommandResponse rechazada(String motivo, String mensaje) {
        return new AiCommandResponse(false, motivo, mensaje, List.of(), null);
    }
}
