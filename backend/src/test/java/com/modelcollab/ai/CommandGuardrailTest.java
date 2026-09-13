package com.modelcollab.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.ai.service.CommandGuardrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El guardarraíl antialucinación de RF-02.6.
 *
 * <p>§19 lo pone como pregunta directa del tribunal: se dicta "créame un sistema para un banco" y
 * el sistema tiene que rechazarlo educadamente en vez de escupir quince clases inventadas. No es
 * una limitación que haya que disculpar — es una decisión de producto: <b>la herramienta modela
 * lo que el usuario dicta, no fantasea dominios enteros</b>.
 *
 * <p>Los dos lados importan lo mismo. Que rechace lo que tiene que rechazar es la mitad; que no
 * bloquee comandos legítimos es la otra, y es la que rompe la herramienta en silencio si falla.
 */
class CommandGuardrailTest {

    private final CommandGuardrail guardarrail = new CommandGuardrail();

    @ParameterizedTest(name = "rechaza: {0}")
    @ValueSource(strings = {
        "Créame un sistema para un banco",
        "Creame un sistema para un banco",
        "hazme un sistema bancario",
        "créame un modelo para un hospital",
        "generá una aplicación de facturación",
        "necesito un ERP completo",
        "armame una base de datos para una veterinaria",
        "hacé el diagrama completo de una biblioteca",
    })
    @DisplayName("Pedir un dominio entero se detecta sin llamar a nadie")
    void pedirUnDominio(String comando) {
        assertThat(guardarrail.pideUnDominioEntero(comando)).isTrue();
    }

    @ParameterizedTest(name = "deja pasar: {0}")
    @ValueSource(strings = {
        "Crea la clase Factura con atributo total decimal",
        "crea la clase Factura con atributo total decimal y fecha date",
        "Agrega el atributo telefono a Proveedor",
        "Mueve la clase Cliente a la derecha",
        "Relaciona Factura con Cliente de muchos a uno",
        "renombra la clase Cliente a Comprador",
        "Empleado hereda de Persona",
        "agrega un paquete llamado ventas",
        "agrega el atributo modelo a Auto",
        "crea la clase Proyecto con atributo nombre texto",
    })
    @DisplayName("Un comando de edición puntual pasa de largo")
    void edicionPuntual(String comando) {
        // Un falso positivo acá es peor que un falso negativo: le niega al usuario un comando
        // válido sin darle ninguna salida, mientras que lo que se escapa cae igual en el tope
        // duro de 5 operaciones y en la lista blanca, que no se pueden esquivar.
        assertThat(guardarrail.pideUnDominioEntero(comando)).isFalse();
    }

    @Test
    @DisplayName("Nombrar una pieza gana: 'sistema' puede ser contexto y no el pedido")
    void mencionarUnElementoGana() {
        // "Sistema" acá es de qué habla el diagrama, no qué se pide generar. Si la sola
        // aparición de la palabra bloqueara, modelar un sistema bancario sería imposible —
        // que es justamente el dominio del ejemplo del tribunal.
        assertThat(guardarrail.pideUnDominioEntero(
                "Agrega la clase Cuenta al sistema bancario")).isFalse();
        assertThat(guardarrail.pideUnDominioEntero(
                "crea la clase Sistema con atributo nombre texto")).isFalse();
    }

    @Test
    @DisplayName("Una tilde no esquiva el guardarraíl")
    void lasTildesNoLoEsquivan() {
        // El comando puede venir de un dictado: el navegador escribe "aplicación" con tilde y
        // el que teclea apurado escribe "aplicacion" sin ella. Las dos son el mismo pedido.
        assertThat(guardarrail.pideUnDominioEntero("creame una aplicación para un gimnasio"))
                .isTrue();
        assertThat(guardarrail.pideUnDominioEntero("creame una aplicacion para un gimnasio"))
                .isTrue();
    }

    @Test
    @DisplayName("El texto del rechazo es el que manda RF-02.6, palabra por palabra")
    void elTextoDelRechazo() {
        // Es la frase que va a escuchar el tribunal. Si alguien la reescribe "para que suene
        // mejor", deja de coincidir con el documento que el docente tiene delante.
        assertThat(CommandGuardrail.RECHAZO).isEqualTo(
                "Soy un asistente de edición puntual, no genero dominios completos desde cero. "
                        + "Por favor indícame clases, atributos o relaciones específicas.");
    }

    @Test
    @DisplayName("Un comando vacío no es un pedido de dominio")
    void vacio() {
        assertThat(guardarrail.pideUnDominioEntero(null)).isFalse();
        assertThat(guardarrail.pideUnDominioEntero("   ")).isFalse();
    }
}
