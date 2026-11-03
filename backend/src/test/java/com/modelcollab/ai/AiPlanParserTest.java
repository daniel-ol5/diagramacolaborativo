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
 * La frontera con la IA se valida con la misma severidad que la de un cliente cualquiera.
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
    @DisplayName("Las operaciones de borrado admitidas (DELETE_CLASS, DELETE_ATTRIBUTE, DELETE_RELATIONSHIP) se parsean")
    void operacionesDeBorradoValidas() {
        AiPlan plan = parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"Borrando.","operations":[
              {"op":"DELETE_CLASS","target":"Factura","args":{}},
              {"op":"DELETE_ATTRIBUTE","target":"Auto","args":{"name":"color"}},
              {"op":"DELETE_RELATIONSHIP","target":"Auto","args":{"to":"Factura"}}
            ]}""");

        assertThat(plan.intent()).isEqualTo(AiPlan.EDITAR);
        assertThat(plan.operations()).hasSize(3);
        assertThat(plan.operations().get(0).op()).isEqualTo("DELETE_CLASS");
        assertThat(plan.operations().get(1).op()).isEqualTo("DELETE_ATTRIBUTE");
        assertThat(plan.operations().get(2).op()).isEqualTo("DELETE_RELATIONSHIP");
    }

    @Test
    @DisplayName("Se le destapa el bloque de código, que es la única indulgencia que hay")
    void jsonEnvueltoEnUnBloqueDeCodigo() {
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
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"DELETE_PACKAGE","target":"Factura","args":{}}]}"""))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
    }

    @Test
    @DisplayName("BULK_MERGE está prohibido: es el guardarraíl al revés")
    void bulkMergeProhibido() {
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"BULK_MERGE","target":"todo","args":{}}]}"""))
                .isInstanceOf(RespuestaDeIaInvalidaException.class);
        assertThat(AiPlanParser.OPERACIONES_PERMITIDAS).doesNotContain("BULK_MERGE");
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
        assertThatThrownBy(() -> parser.parsear("""
            {"intent":"EDIT_MODEL","reply":"x","operations":[
              {"op":"ADD_CLASS","target":"A","args":{}},
              {"op":"ADD_CLASS","target":"B","args":{}},
              {"op":"NOPE","target":"C","args":{}}]}"""))
                .hasMessageContaining("La operación 3");
    }
}
