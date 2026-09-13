package com.modelcollab.ai.service;

import java.util.List;

/**
 * La frontera con el servicio de IA. <b>Devuelve texto crudo, no un objeto.</b>
 *
 * <h2>Por qué el texto sin parsear</h2>
 *
 * <p>Si esta interfaz devolviera un {@code AiPlan} ya armado, el parseo quedaría dentro de cada
 * implementación y <b>una respuesta malformada no se podría ni representar</b> — con lo cual
 * tampoco se podría probar. Y el caso que más importa cubrir es justamente ése: que el modelo
 * devuelva algo que no es JSON, o JSON con un campo que el esquema no define.
 *
 * <p>Con el texto crudo la costura cae exactamente donde está la desconfianza. De acá para
 * adentro es territorio ajeno; de acá para afuera valida {@link AiPlanParser} con la misma
 * severidad que se le aplica a cualquier cliente. Un test puede devolver basura por esta
 * interfaz y comprobar que no llega al modelo canónico.
 */
public interface AiModelClient {

    /**
     * @param comando lo que escribió o dictó el usuario
     * @param clasesDelDiagrama nombres de las clases que hoy existen, para que el modelo se
     *     refiera a las que están en vez de inventar
     * @return el texto que devolvió el modelo, sin tocar
     */
    String responder(String comando, List<String> clasesDelDiagrama);

    /** Para el log de arranque y para que la interfaz pueda avisar que está en modo simulado. */
    String descripcion();

    /** Si esto no habla con ningún servicio externo. */
    boolean esSimulado();
}
