package com.modelcollab.diagram.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.diagram.model.DiagramOperation;
import com.modelcollab.metamodel.model.CanonicalModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduce identificadores a nombres legibles para el reporte de reconciliación.
 *
 * <h2>Por qué hace falta reconstruir</h2>
 *
 * <p>§9.2 exige mensajes como <i>"la clase 'Cliente' fue eliminada por otro colaborador"</i>. El
 * problema es que, para cuando hay que escribir esa frase, <b>la clase ya no existe</b> — se
 * descartó la operación justamente porque otro la borró. Buscar el nombre en el estado actual no
 * sirve: ahí no está.
 *
 * <p>Donde sí está es en la <b>bitácora de operaciones</b>, que es append-only: el
 * {@code ADD_CLASS} que la creó lleva su nombre en el payload, y los {@code RENAME_CLASS}
 * posteriores llevan el nuevo. Reproduciendo el log en orden se obtiene el último nombre que
 * tuvo cada elemento antes de desaparecer.
 *
 * <p>Se construye <b>sólo si hay algo que descartar</b>: en el camino feliz, que es la mayoría,
 * no se lee la bitácora.
 */
class ResolutorDeNombres {

    private final Map<String, String> nombres = new HashMap<>();

    ResolutorDeNombres(
            CanonicalModel estadoActual, List<DiagramOperation> bitacora, ObjectMapper mapper) {
        // 1) La bitácora primero, en orden cronológico: recupera lo que ya no existe.
        for (DiagramOperation operacion : bitacora) {
            registrarDesdeLaBitacora(operacion, mapper);
        }
        // 2) El estado actual encima: es autoritativo para todo lo que sigue vivo.
        registrarDesdeElEstado(estadoActual);
    }

    /**
     * Nombre entre comillas simples, o el identificador si no se pudo resolver. Nunca devuelve
     * null ni vacío: un mensaje al usuario no puede tener un hueco.
     */
    String de(String id) {
        if (id == null) {
            return "(sin identificar)";
        }
        String nombre = nombres.get(id);
        return nombre != null ? "'" + nombre + "'" : "'" + id + "'";
    }

    boolean conoce(String id) {
        return id != null && nombres.containsKey(id);
    }

    private void registrarDesdeElEstado(CanonicalModel modelo) {
        modelo.classes().forEach(c -> {
            nombres.put(c.id(), c.name());
            c.attributes().forEach(a -> nombres.put(a.id(), a.name()));
            c.methods().forEach(m -> nombres.put(m.id(), m.name()));
        });
        modelo.packages().forEach(p -> nombres.put(p.id(), p.name()));
        modelo.relationships().forEach(r -> nombres.putIfAbsent(r.id(), "relación"));
    }

    private void registrarDesdeLaBitacora(DiagramOperation operacion, ObjectMapper mapper) {
        JsonNode payload;
        try {
            payload = mapper.readTree(operacion.getPayload());
        } catch (Exception e) {
            return; // una fila ilegible no puede tumbar el reporte entero
        }
        String nombre = texto(payload, "name");
        if (nombre == null) {
            return;
        }
        // Las operaciones de alta traen el id del elemento nuevo dentro del payload; las de
        // modificación lo traen en target_id, porque su payload sólo lleva los campos que cambian.
        String idEnElPayload = texto(payload, "id");
        if (idEnElPayload != null) {
            nombres.put(idEnElPayload, nombre);
        } else if (operacion.getTargetId() != null) {
            nombres.put(operacion.getTargetId(), nombre);
        }
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor == null || !valor.isTextual() ? null : valor.asText();
    }
}
