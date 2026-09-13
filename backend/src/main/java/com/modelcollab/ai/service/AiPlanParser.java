package com.modelcollab.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.ai.dto.AiCommandResponse;
import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.AiPlan;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Valida lo que devolvió el modelo de lenguaje, con la misma severidad que a cualquier cliente.
 *
 * <h2>Por qué no alcanza con pedirle JSON</h2>
 *
 * <p>Se le pide esquema fijo y temperatura 0, y aun así puede devolver un {@code op} que no
 * existe, un campo que el esquema no define o veinte operaciones cuando se le pidió una. No es
 * mala fe: es que un modelo de lenguaje no tiene ninguna obligación estructural de cumplir.
 *
 * <p>Y lo que hay del otro lado no es un formulario cualquiera: es el <b>modelo canónico</b>, que
 * sostiene el lienzo de todos los colaboradores, el generador de código y el exportador XMI. Una
 * clase con un tipo de atributo inventado no explota acá — explota mucho más tarde, como un ZIP
 * que no compila en la defensa, sin ningún rastro de que lo puso la IA.
 *
 * <p>Por eso la frontera con la IA se trata igual que la frontera con un navegador: nada entra
 * sin comprobarse. La diferencia es que del navegador sospechamos por seguridad y del modelo
 * sospechamos por naturaleza.
 */
@Component
public class AiPlanParser {

    /**
     * <b>Lista blanca</b> de operaciones que la IA puede pedir. Es un subconjunto chico de los 22
     * de §8.1, y las ausencias son deliberadas.
     *
     * <p>No hay ningún {@code DELETE_}. RF-02 pide crear, modificar, mover y relacionar, y nada
     * más. Un comando dictado por voz pasa por un reconocedor que confunde palabras: la
     * diferencia entre "borra la clase Cliente" y algo que suene parecido es el trabajo de otra
     * persona. Borrar sigue siendo un gesto deliberado en el lienzo, donde se ve qué se
     * selecciona antes de confirmarlo.
     *
     * <p>Tampoco hay {@code BULK_MERGE}: es la operación que reemplaza el diagrama entero, o sea
     * exactamente lo que el guardarraíl de RF-02.6 existe para impedir. Dejarla entrar por la
     * puerta de la IA sería poner el candado y regalar la llave.
     */
    public static final Set<String> OPERACIONES_PERMITIDAS = Set.of(
            "ADD_CLASS", "ADD_ATTRIBUTE", "RENAME_CLASS", "MOVE_CLASS", "ADD_RELATIONSHIP");

    private static final Set<String> INTENCIONES = Set.of(
            AiPlan.EDITAR, AiPlan.GENERAR_DOMINIO, AiPlan.NO_SOPORTADO);

    private final ObjectMapper objectMapper;

    public AiPlanParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiPlan parsear(String crudo) {
        if (crudo == null || crudo.isBlank()) {
            throw roto("El servicio de IA devolvió una respuesta vacía");
        }

        AiPlan plan;
        try {
            plan = objectMapper.readValue(desenvolverBloqueDeCodigo(crudo), AiPlan.class);
        } catch (JsonProcessingException e) {
            // El mapper canónico tiene FAIL_ON_UNKNOWN_PROPERTIES: un campo de más entra por
            // acá, igual que un JSON que directamente no parsea. Las dos cosas significan lo
            // mismo —la respuesta no cumple el contrato— y se tratan igual.
            throw new RespuestaDeIaInvalidaException(
                    AiCommandResponse.CONTRATO_ROTO,
                    "El servicio de IA devolvió algo que no respeta el formato acordado: "
                            + e.getOriginalMessage(),
                    e);
        }
        if (plan == null) {
            throw roto("El servicio de IA devolvió un JSON vacío");
        }

        if (plan.intent() == null || !INTENCIONES.contains(plan.intent())) {
            throw roto("El servicio de IA devolvió una intención que no existe: " + plan.intent());
        }

        List<AiOperation> operaciones = plan.operations();
        for (int i = 0; i < operaciones.size(); i++) {
            validar(operaciones.get(i), i);
        }
        return plan;
    }

    private void validar(AiOperation operacion, int indice) {
        String donde = "La operación " + (indice + 1);

        if (operacion.op() == null || operacion.op().isBlank()) {
            throw roto(donde + " no dice qué operación es");
        }
        if (!OPERACIONES_PERMITIDAS.contains(operacion.op())) {
            // Cubre las dos cosas a la vez, y no hace falta distinguirlas: una operación que no
            // existe en el catálogo y una que existe pero la IA no tiene permitido pedir se
            // rechazan igual. Lo que importa es que sólo pasa lo que está en la lista.
            throw roto(donde + " pide '" + operacion.op()
                    + "', que no es una operación que el asistente pueda hacer");
        }
        if (operacion.target() == null || operacion.target().isBlank()) {
            throw roto(donde + " (" + operacion.op() + ") no dice sobre qué clase actúa");
        }
    }

    /**
     * La única normalización que se le permite al texto del modelo: sacarle el bloque de código.
     *
     * <p>Es publica y la comparte CU17: tiene que haber <b>un solo lugar</b> donde el sistema
     * afloje con el texto de un modelo. Con una copia por caso de uso, alguien relaja una y la
     * otra queda distinta sin que nadie se entere.
     *
     * <p>Los modelos devuelven a veces el JSON envuelto en ```json … ``` aunque se les pida sólo
     * JSON. Es una peculiaridad conocida del formato de salida, no un desvío del contenido, así
     * que se destapa y listo. <b>Nada más se tolera</b>: no se intenta reparar comillas, ni
     * recortar texto de alrededor, ni adivinar dónde empieza el objeto. Un parser indulgente con
     * la basura termina interpretando basura, y acá lo que se interpreta se escribe en el
     * modelo canónico.
     */
    public static String desenvolverBloqueDeCodigo(String crudo) {
        String texto = crudo.strip();
        if (!texto.startsWith("```")) {
            return texto;
        }
        int primerSalto = texto.indexOf('\n');
        int cierre = texto.lastIndexOf("```");
        if (primerSalto < 0 || cierre <= primerSalto) {
            return texto;
        }
        return texto.substring(primerSalto + 1, cierre).strip();
    }

    private static RespuestaDeIaInvalidaException roto(String mensaje) {
        return new RespuestaDeIaInvalidaException(AiCommandResponse.CONTRATO_ROTO, mensaje);
    }
}
