package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.ai.dto.AiPlan;
import com.modelcollab.ai.service.AiPlanParser;
import com.modelcollab.ai.service.RespuestaDeIaInvalidaException;
import com.modelcollab.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <b>La frontera con la IA se valida con la misma severidad que la de un cliente cualquiera.</b>
 *
 * <p>Se le pide al modelo esquema fijo y temperatura 0, y aun así puede devolver cualquier cosa:
 * no tiene ninguna obligación estructural de cumplir. Lo que hay del otro lado es el modelo
 * canónico, que sostiene el lienzo de todos, el generador y el exportador — una clase con un
 * tipo inventado no explota acá, explota mucho después como un ZIP que no compila.
 *
 * <p>Por eso los casos que importan en este archivo son <b>los malos</b>. Que el camino feliz
 * funcione no prueba nada sobre el día que el servicio devuelva basura.
 */
class AiPlanParserTest {

    private final AiPlanParser parser = new AiPlanParser(JacksonConfig.canonicalObjectMapper());

    // ------------------------------------------------------------ lo que sí

    @Test
    @DisplayName("Un plan bien formado se parsea entero")
    void planValido() {
        AiPlan plan = parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"Creo Factura.","operations":[
              {"op":"ADD_CLASS","target":"Factura","args":{}},
              {"op":"ADD_ATTRIBUTE","target":"Factura","args":{"name":"total","type":"DECIMAL"}}
            ]}""");

        assertThat(plan.intent()).isEqualTo(AiPlan.EDITAR);
        assertThat(plan.operations()).hasSize(2);
        assertThat(plan.operations().get(1).texto("type")).isEqualTo("DECIMAL");
    }

    @Test
    @DisplayName("Se le destapa el bloque de código, que es la única indulgencia que hay")
    void jsonEnvueltoEnUnBloqueDeCodigo() {
        // Los modelos devuelven a veces ```json … ``` aunque se les pida sólo JSON. Es una
        // peculiaridad del formato de salida, no del contenido.
        AiPlan plan = parser.parsear("""
            ```json
            {"intent":"UNSUPPORTED","reply":"No sé.","operations":[]}
            ```""");

        assertThat(plan.intent()).isEqualTo(AiPlan.NO_SOPORTADO);
    }

    // ------------------------------------------------------------ lo que no

    @ParameterizedTest(name = "rechaza: {0}")
    @ValueSource(strings = {
        "esto no es json",
        "{\"intent\":\"EDIT_MODEL\",",
        "<html><body>502 Bad Gateway</body></html>",
        "",
        "   ",
    })
    @DisplayName("Una respuesta que no es JSON no entra")
    void jsonMalformado(String crudo) {
        assertThatThrownBy(() -> parser.parsear(crudo))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
    }

    @Test
    @DisplayName("Un campo que el esquema no define se rechaza en vez de ignorarse")
    void campoDesconocido() {
        // FAIL_ON_UNKNOWN_PROPERTIES del mapper canónico. Ignorarlo sería peor que fallar: un
        // campo que el modelo inventa y que nadie lee es una instrucción que se pierde en
        // silencio, y nadie se entera hasta que falta el dato.
        assertThatThrownBy(() -> parser.parsear(
                "{\"intent\":\"EDIT_MODEL\",\"reply\":\"x\",\"operations\":[],\"extra\":1}"))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
    }

    @Test
    @DisplayName("Una intención que no existe se rechaza")
    void intencionInventada() {
        assertThatThrownBy(() -> parser.parsear(
                "{\"intent\":\"CREATE_EVERYTHING\",\"reply\":\"x\",\"operations\":[]}"))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("CREATE_EVERYTHING");
    }

    @Test
    @DisplayName("Una operación que no está en el catálogo de §8.1 no entra")
    void operacionInventada() {
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"DROP_DATABASE","target":"Factura","args":{}}]}"""))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("DROP_DATABASE");
    }

    @Test
    @DisplayName("Una operación del catálogo pero fuera de la lista blanca tampoco entra")
    void operacionRealPeroNoPermitida() {
        // DELETE_CLASS existe en §8.1 y el aplicador la sabe hacer. La IA igual no puede
        // pedirla: un comando dictado pasa por un reconocedor de voz que confunde palabras, y
        // borrar tiene que seguir siendo un gesto deliberado en el lienzo.
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"DELETE_CLASS","target":"Factura","args":{}}]}"""))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
    }

    @Test
    @DisplayName("BULK_MERGE está prohibido: es el guardarraíl al revés")
    void bulkMergeProhibido() {
        // BULK_MERGE reemplaza el diagrama entero. Dejarla entrar por la puerta de la IA sería
        // poner el candado de RF-02.6 y regalar la llave: un solo "op" y el modelo pisa todo.
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"BULK_MERGE","target":"todo","args":{}}]}"""))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
        assertThat(AiPlanParser.OPERACIONES_PERMITIDAS).doesNotContain("BULK_MERGE");
    }

    @Test
    @DisplayName("Ninguna operación de borrado está permitida")
    void ningunBorrado() {
        assertThat(AiPlanParser.OPERACIONES_PERMITIDAS)
                .as("un comando mal escuchado no puede destruir trabajo ajeno")
                .noneMatch(op -> op.startsWith("DELETE_"));
    }

    @Test
    @DisplayName("Una operación sin objetivo se rechaza")
    void sinObjetivo() {
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_ATTRIBUTE","target":null,"args":{"name":"total","type":"DECIMAL"}}]}"""))
                .isInstanceOf(RespuestaDeIaInvalidaException.class)
                .hasMessageContaining("sobre qué clase");
    }

    @Test
    @DisplayName("El mensaje del rechazo dice cuál de las operaciones estaba mal")
    void elMensajeUbicaElProblema() {
        // Con cinco operaciones y un mensaje que dice sólo "operación inválida", encontrar cuál
        // es trabajo de adivinanza. El número lo resuelve.
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_CLASS","target":"A","args":{}},
              {"op":"ADD_CLASS","target":"B","args":{}},
              {"op":"NOPE","target":"C","args":{}}]}"""))
                .hasMessageContaining("La operación 3");
    }
}
