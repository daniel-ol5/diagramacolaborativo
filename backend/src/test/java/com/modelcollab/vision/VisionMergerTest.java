package com.modelcollab.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.modelcollab.ai.service.GridLayoutEngine;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.vision.dto.VisionAttribute;
import com.modelcollab.vision.dto.VisionClass;
import com.modelcollab.vision.dto.VisionDraft;
import com.modelcollab.vision.dto.VisionRelationship;
import com.modelcollab.vision.service.VisionMerger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La fusión de lo leído en la foto con lo que ya estaba dibujado.
 *
 * <p>Dos propiedades cargan con todo el peso de este archivo. La primera es que <b>sólo
 * agrega</b>: es lo que hace que importar una foto en una sala con gente trabajando no pueda
 * pisarle nada a nadie. La segunda es la asimetría entre proponer y confirmar — blando donde una
 * persona va a revisar, duro donde ya se escribe en el modelo canónico.
 */
class VisionMergerTest {

    private final VisionMerger fusionador = new VisionMerger(new GridLayoutEngine());

    private static CanonicalModel diagramaConCliente() {
        ClassEntity cliente = new ClassEntity(
                "cliente-1", "Cliente", null, false, new Position(20, 20), null, null,
                List.of(new Attribute("a1", "nombre", AttributeType.VARCHAR,
                        null, null, null, null, false, true, null, null)),
                List.of());
        return new CanonicalModel("1.0.0", 5L, List.of(), List.of(cliente), List.of());
    }

    private static VisionDraft borrador(VisionClass... clases) {
        return new VisionDraft(List.of(clases), List.of());
    }

    // --------------------------------------------------------------- sólo agrega

    @Test
    @DisplayName("Lo que ya estaba queda EXACTAMENTE igual: la foto no puede pisar a nadie")
    void soloAgrega() {
        CanonicalModel antes = diagramaConCliente();

        VisionMerger.Resultado resultado = fusionador.fusionar(
                antes, borrador(new VisionClass("Pedido", List.of())), false);

        // Ésta es la propiedad que permite aplicar la foto como un BULK_MERGE —que reemplaza el
        // estado— sin que reemplazar signifique perder. Si alguna vez esta afirmación deja de
        // valer, la operación pasa a poder borrar trabajo ajeno en silencio.
        assertThat(resultado.modelo().classes()).hasSize(2);
        assertThat(resultado.modelo().classes().get(0)).isEqualTo(antes.classes().get(0));
        assertThat(resultado.clasesNuevas()).isEqualTo(1);
    }

    @Test
    @DisplayName("Las clases nuevas no se apilan: la grilla las separa")
    void noSeApilan() {
        VisionMerger.Resultado resultado = fusionador.fusionar(
                diagramaConCliente(),
                borrador(new VisionClass("Pedido", List.of()),
                        new VisionClass("Item", List.of()),
                        new VisionClass("Producto", List.of())),
                false);

        // Una foto no trae coordenadas utilizables: las de la cartulina dependen del ángulo de
        // la cámara. Sin grilla, las tres caen en el mismo punto y la función es inservible
        // aunque el reconocimiento haya sido perfecto.
        List<Position> posiciones = resultado.modelo().classes().stream()
                .map(ClassEntity::position).toList();
        assertThat(posiciones).doesNotHaveDuplicates();
    }

    // ------------------------------------------------ blando al proponer

    @Test
    @DisplayName("Al proponer, un tipo ilegible pasa a VARCHAR y se avisa")
    void tipoIlegibleAlProponer() {
        VisionMerger.Resultado resultado = fusionador.fusionar(
                diagramaConCliente(),
                borrador(new VisionClass("Pedido",
                        List.of(new VisionAttribute("total", "decmal")))),
                false);

        // Tirar la foto entera porque una palabra escrita a mano salió mal haría la función
        // inútil: RF-03.3 pone a una persona justamente a corregir los tipos mal detectados.
        ClassEntity pedido = resultado.modelo().classes().get(1);
        assertThat(pedido.attributes().get(0).type()).isEqualTo(AttributeType.VARCHAR);
        assertThat(resultado.notas()).anyMatch(n -> n.contains("decmal"));
    }

    @Test
    @DisplayName("Al proponer, una línea hacia una clase que no está se descarta con su motivo")
    void lineaColgadaAlProponer() {
        VisionDraft conLineaColgada = new VisionDraft(
                List.of(new VisionClass("Pedido", List.of())),
                List.of(new VisionRelationship("Pedido", "Deposito", "ASSOCIATION", null, null)));

        VisionMerger.Resultado resultado =
                fusionador.fusionar(diagramaConCliente(), conLineaColgada, false);

        // Una flecha que se sale del borde de la foto es lo más común del mundo. Dejarla
        // entrar produciría una relación huérfana, que es uno de los errores bloqueantes de CU13.
        assertThat(resultado.relacionesNuevas()).isZero();
        assertThat(resultado.notas()).anyMatch(n -> n.contains("Deposito"));
    }

    @Test
    @DisplayName("Al proponer, un nombre que ya existe se renombra y se avisa")
    void nombreRepetidoAlProponer() {
        VisionMerger.Resultado resultado = fusionador.fusionar(
                diagramaConCliente(), borrador(new VisionClass("Cliente", List.of())), false);

        // Fusionar los atributos dentro de la clase que ya existe sería una sorpresa: nadie
        // pidió modificar lo que ya estaba dibujado.
        assertThat(resultado.modelo().classes()).extracting(ClassEntity::name)
                .containsExactly("Cliente", "Cliente (2)");
        assertThat(resultado.notas()).anyMatch(n -> n.contains("Cliente (2)"));
    }

    @Test
    @DisplayName("Una relación puede enganchar con una clase que YA estaba en el diagrama")
    void seEnganchaConLoQueYaHabia() {
        // Media pizarra que se conecta con lo que ya había dibujado es un caso real.
        VisionDraft draft = new VisionDraft(
                List.of(new VisionClass("Pedido", List.of())),
                List.of(new VisionRelationship("Pedido", "Cliente", "ASSOCIATION", "0..*", "1..1")));

        VisionMerger.Resultado resultado =
                fusionador.fusionar(diagramaConCliente(), draft, false);

        assertThat(resultado.relacionesNuevas()).isEqualTo(1);
        assertThat(resultado.modelo().relationships().get(0).targetClassId())
                .isEqualTo("cliente-1");
    }

    // ------------------------------------------------- duro al confirmar

    @Test
    @DisplayName("Al confirmar, un tipo inválido es un rechazo")
    void tipoInvalidoAlConfirmar() {
        // Ya hubo alguien mirando. Lo que llega ahora no es una lectura para revisar: es una
        // escritura sobre el modelo canónico, y viene de un navegador como cualquier otra.
        assertThatThrownBy(() -> fusionador.fusionar(
                diagramaConCliente(),
                borrador(new VisionClass("Pedido",
                        List.of(new VisionAttribute("total", "decmal")))),
                true))
                .isInstanceOf(VisionMerger.BorradorInvalidoException.class)
                .hasMessageContaining("decmal");
    }

    @Test
    @DisplayName("Al confirmar, una línea colgada es un rechazo y no un descarte silencioso")
    void lineaColgadaAlConfirmar() {
        VisionDraft draft = new VisionDraft(
                List.of(new VisionClass("Pedido", List.of())),
                List.of(new VisionRelationship("Pedido", "Deposito", "ASSOCIATION", null, null)));

        assertThatThrownBy(() -> fusionador.fusionar(diagramaConCliente(), draft, true))
                .isInstanceOf(VisionMerger.BorradorInvalidoException.class)
                .hasMessageContaining("Deposito");
    }

    @Test
    @DisplayName("Al confirmar, chocar con un nombre existente es un rechazo con instrucción")
    void nombreRepetidoAlConfirmar() {
        assertThatThrownBy(() -> fusionador.fusionar(
                diagramaConCliente(), borrador(new VisionClass("Cliente", List.of())), true))
                .isInstanceOf(VisionMerger.BorradorInvalidoException.class)
                .hasMessageContaining("Cambiale el nombre");
    }

    @Test
    @DisplayName("Un borrador sin ninguna clase se rechaza en los dos modos")
    void borradorVacio() {
        VisionDraft vacio = new VisionDraft(List.of(), List.of());

        assertThatThrownBy(() -> fusionador.fusionar(diagramaConCliente(), vacio, false))
                .isInstanceOf(VisionMerger.BorradorInvalidoException.class)
                .hasMessageContaining("nítida");
        assertThatThrownBy(() -> fusionador.fusionar(diagramaConCliente(), vacio, true))
                .isInstanceOf(VisionMerger.BorradorInvalidoException.class);
    }

    @Test
    @DisplayName("El borrador saneado que vuelve es el que se puede confirmar tal cual")
    void elSaneadoEsConfirmable() {
        // Si la propuesta devolviera el texto crudo del modelo, la persona apretaría confirmar
        // y recibiría un rechazo por el mismo tipo que el sistema ya había decidido corregir.
        VisionDraft crudo = new VisionDraft(
                List.of(new VisionClass("Pedido",
                        List.of(new VisionAttribute("total", "decmal")))),
                List.of(new VisionRelationship("Pedido", "Deposito", "ASSOCIATION", null, null)));

        VisionDraft saneado =
                fusionador.fusionar(diagramaConCliente(), crudo, false).borradorSaneado();

        assertThat(fusionador.fusionar(diagramaConCliente(), saneado, true).clasesNuevas())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("La herencia no lleva multiplicidades, igual que en los fixtures de referencia")
    void herenciaSinMultiplicidades() {
        VisionDraft draft = new VisionDraft(
                List.of(new VisionClass("Empresa", List.of())),
                List.of(new VisionRelationship("Empresa", "Cliente", "INHERITANCE", null, null)));

        VisionMerger.Resultado resultado = fusionador.fusionar(diagramaConCliente(), draft, true);

        assertThat(resultado.modelo().relationships().get(0))
                .satisfies(r -> {
                    assertThat(r.type()).isEqualTo(RelationshipType.INHERITANCE);
                    assertThat(r.sourceMultiplicity()).isNull();
                    assertThat(r.targetMultiplicity()).isNull();
                });
    }
}
