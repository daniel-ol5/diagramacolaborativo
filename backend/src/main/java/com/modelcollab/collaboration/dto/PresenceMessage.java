package com.modelcollab.collaboration.dto;

import java.util.List;

/**
 * Quiénes están en la sala. Se difunde cada vez que alguien entra o sale.
 *
 * <p>Va la <b>lista completa</b> y no un "entró fulano" / "salió mengano". Con eventos
 * incrementales, un cliente que se pierde uno queda con un roster equivocado para siempre y sin
 * forma de notarlo — dibujando el cursor de alguien que ya se fue, o sin el de alguien que está.
 * Mandar la lista entera cuesta unos bytes más y hace que cualquier mensaje recibido deje al
 * cliente en el estado correcto, sin importar cuántos se haya perdido antes.
 *
 * <p>Cada participante trae su {@code color}, el mismo que usan sus locks y su cursor: es lo que
 * permite que el candado de una clase, el puntero y la entrada en la lista de presentes se
 * reconozcan como la misma persona de un vistazo.
 */
public record PresenceMessage(String event, List<Participante> participants) {

    public static final String PRESENCE = "PRESENCE";

    public static PresenceMessage de(List<Participante> participantes) {
        return new PresenceMessage(PRESENCE, participantes);
    }
}
