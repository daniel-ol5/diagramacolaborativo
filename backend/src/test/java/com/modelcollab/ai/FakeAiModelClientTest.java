package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.ai.dto.AiOperation;
import com.modelcollab.ai.dto.AiPlan;
import com.modelcollab.ai.service.AiPlanParser;
import com.modelcollab.ai.service.CommandGuardrail;
import com.modelcollab.ai.service.FakeAiModelClient;
import com.modelcollab.config.JacksonConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El cliente simulado: reglas y ranuras, sin red ni clave.
 *
 * <p>Es lo que permite que CU16 funcione de punta a punta mientras la clave de Gemini no llegue,
 * y lo que hace que el guión de §19 se pueda ensayar sin internet. Cada test acá es una de las
 * formas de comando que enumera RF-02.
 *
 * <p>La respuesta se pasa por el parser de verdad y no se inspecciona el objeto directamente:
 * este cliente tiene que producir algo que el validador acepte, igual que tendría que hacerlo
 * Gemini. Un simulado que devolviera un objeto ya armado probaría un camino que en producción
 * no existe.
 */
class FakeAiModelClientTest {

    private final AiPlanParser parser = new AiPlanParser(JacksonConfig.canonicalObjectMapper());
    private final FakeAiModelClient cliente =
            new FakeAiModelClient(new CommandGuardrail(), JacksonConfig.canonicalObjectMapper());

    private AiPlan interpretar(String comando) {
        return parser.parsear(cliente.responder(comando, List.of("Cliente", "Proveedor")));
    }

    @Test
    @DisplayName("RF-02.2: el comando del guión crea la clase y su atributo")
    void elComandoDelGuion() {
        // Es literalmente lo que se dicta en §19, minuto 2:00.
        AiPlan plan = interpretar("Crea la clase Factura con atributo total decimal");

        assertThat(plan.intent()).isEqualTo(AiPlan.EDITAR);
        assertThat(plan.operations()).hasSize(2);
        assertThat(plan.operations().get(0).op()).isEqualTo("ADD_CLASS");
        assertThat(plan.operations().get(0).target()).isEqualTo("Factura");
        assertThat(plan.operations().get(1).op()).isEqualTo("ADD_ATTRIBUTE");
        assertThat(plan.operations().get(1).texto("name")).isEqualTo("total");
        assertThat(plan.operations().get(1).texto("type")).isEqualTo("DECIMAL");
    }

    @Test
    @DisplayName("Varios atributos en un comando se separan por coma y por 'y'")
    void variosAtributos() {
        AiPlan plan = interpretar(
                "crea la clase Factura con atributos total decimal, fecha date y activa booleano");

        assertThat(plan.operations()).hasSize(4);
        assertThat(plan.operations().stream().map(o -> o.texto("type")).toList())
                .containsExactly(null, "DECIMAL", "DATE", "BOOLEAN");
    }

    @Test
    @DisplayName("Un atributo sin tipo cae en VARCHAR en vez de inventar uno")
    void atributoSinTipo() {
        // Ante una palabra que no está en la lista de tipos, lo seguro es asumir que el usuario
        // no dijo el tipo — no que dijo uno raro que habría que adivinar.
        AiPlan plan = interpretar("crea la clase Factura con atributo observaciones");

        assertThat(plan.operations().get(1).texto("type")).isEqualTo("VARCHAR");
    }

    @Test
    @DisplayName("RF-02.3: agregar un atributo a una clase que ya existe")
    void agregarAtributo() {
        AiPlan plan = interpretar("Agrega el atributo telefono varchar a Proveedor");

        assertThat(plan.operations()).singleElement()
                .satisfies(o -> {
                    assertThat(o.op()).isEqualTo("ADD_ATTRIBUTE");
                    assertThat(o.target()).isEqualTo("Proveedor");
                    assertThat(o.texto("name")).isEqualTo("telefono");
                });
    }

    @Test
    @DisplayName("RF-02.3: mover")
    void mover() {
        AiPlan plan = interpretar("Mueve la clase Cliente a la derecha");

        AiOperation op = plan.operations().get(0);
        assertThat(op.op()).isEqualTo("MOVE_CLASS");
        assertThat(op.target()).isEqualTo("Cliente");
        assertThat(op.texto("direction")).isEqualTo("RIGHT");
    }

    @Test
    @DisplayName("RF-02.4: relacionar de muchos a uno")
    void relacionar() {
        AiPlan plan = interpretar("Relaciona Factura con Cliente de muchos a uno");

        AiOperation op = plan.operations().get(0);
        assertThat(op.op()).isEqualTo("ADD_RELATIONSHIP");
        assertThat(op.target()).isEqualTo("Factura");
        assertThat(op.texto("to")).isEqualTo("Cliente");
        assertThat(op.texto("type")).isEqualTo("ASSOCIATION");
        assertThat(op.texto("sourceMultiplicity")).isEqualTo("0..*");
        assertThat(op.texto("targetMultiplicity")).isEqualTo("1..1");
    }

    @Test
    @DisplayName("Muchos a muchos da MANY_TO_MANY, no una asociación cualquiera")
    void muchosAMuchos() {
        // Importa: MANY_TO_MANY es la única relación donde owningSide es autoritativo (D-08) y
        // la que hace que el generador cree la tabla intermedia.
        AiPlan plan = interpretar("relaciona Cliente con Proveedor de muchos a muchos");

        assertThat(plan.operations().get(0).texto("type")).isEqualTo("MANY_TO_MANY");
    }

    @Test
    @DisplayName("Renombrar y heredar")
    void renombrarYHeredar() {
        assertThat(interpretar("renombra la clase Cliente a Comprador").operations().get(0))
                .satisfies(o -> {
                    assertThat(o.op()).isEqualTo("RENAME_CLASS");
                    assertThat(o.texto("name")).isEqualTo("Comprador");
                });

        assertThat(interpretar("Empleado hereda de Persona").operations().get(0))
                .satisfies(o -> {
                    assertThat(o.op()).isEqualTo("ADD_RELATIONSHIP");
                    assertThat(o.texto("type")).isEqualTo("INHERITANCE");
                });
    }

    @Test
    @DisplayName("Pedir un dominio entero devuelve GENERATE_DOMAIN y ninguna operación")
    void pedirUnDominio() {
        AiPlan plan = interpretar("Créame un sistema para un banco");

        assertThat(plan.intent()).isEqualTo(AiPlan.GENERAR_DOMINIO);
        assertThat(plan.operations()).isEmpty();
        assertThat(plan.reply()).isEqualTo(CommandGuardrail.RECHAZO);
    }

    @Test
    @DisplayName("Lo que no entiende lo dice, en vez de inventar algo parecido")
    void loQueNoEntiende() {
        // Un analizador de reglas que "hace lo que más se le parece" es peor que uno que se
        // planta: aplica algo que el usuario no pidió y encima con su confirmación implícita.
        AiPlan plan = interpretar("hacé que el diagrama quede más lindo");

        assertThat(plan.intent()).isEqualTo(AiPlan.NO_SOPORTADO);
        assertThat(plan.operations()).isEmpty();
    }

    @Test
    @DisplayName("El mismo comando da siempre el mismo plan")
    void esDeterminista() {
        String comando = "crea la clase Factura con atributo total decimal";

        assertThat(cliente.responder(comando, List.of()))
                .isEqualTo(cliente.responder(comando, List.of()));
    }

    @Test
    @DisplayName("No se autolimita al tope: dejar que el tope lo rechace es lo que lo prueba")
    void noSeAutolimita() {
        // Si el cliente recortara a 5, el guardarraíl nunca se ejercitaría y el test del tope
        // pasaría por una razón falsa. Que produzca de más es justamente lo que hay que poder
        // probar.
        AiPlan plan = interpretar(
                "crea la clase Enorme con atributos a int, b int, c int, d int, e int y f int");

        assertThat(plan.operations()).hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("Se declara simulado: una degradación silenciosa sería peor que el fallo")
    void seDeclaraSimulado() {
        assertThat(cliente.esSimulado()).isTrue();
        assertThat(cliente.descripcion()).contains("simulado");
    }
}
