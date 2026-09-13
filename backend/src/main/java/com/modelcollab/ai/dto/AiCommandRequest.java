package com.modelcollab.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda el navegador a {@code POST /api/v1/diagrams/{id}/ai-command} (§14).
 *
 * <p>Es el mismo contrato para voz y para texto, y CU16 los agrupa a propósito: el
 * reconocimiento de voz del navegador produce texto, así que de acá para adentro no hay ninguna
 * diferencia. El micrófono es un dispositivo de entrada, no otro caso de uso.
 *
 * <p>{@code clientCommandId} lo genera el cliente y sirve para lo mismo que el
 * {@code clientMutationId} del lienzo: si la respuesta se pierde y el usuario reintenta, el
 * comando no se aplica dos veces. Sin él, apretar dos veces "enviar" crea dos clases Factura.
 *
 * <p>El límite de 500 caracteres no es decorativo: una edición puntual no necesita más, y un
 * texto largo es justamente la forma que tiene un pedido de generar un dominio entero.
 */
public record AiCommandRequest(
        @NotBlank(message = "El comando no puede estar vacío")
        @Size(max = 500, message = "El comando no puede pasar de 500 caracteres")
        String command,
        String clientCommandId) {}
