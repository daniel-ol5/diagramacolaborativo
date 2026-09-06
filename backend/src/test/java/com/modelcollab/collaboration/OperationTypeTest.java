package com.modelcollab.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.modelcollab.collaboration.dto.OperationType;
import com.modelcollab.collaboration.dto.OperationType.Exclusion;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Congela la tabla de §8.1. Si alguien cambia la política de exclusión de una operación sin
 * querer, el fallo real aparecería recién con dos usuarios editando a la vez el mismo elemento —
 * la clase de bug que no se reproduce en el escritorio de nadie. Acá aparece en el acto.
 */
class OperationTypeTest {

    @Test
    @DisplayName("El catálogo tiene las 22 operaciones de §8.1")
    void tieneLasVeintidos() {
        assertThat(OperationType.values()).hasSize(22);
    }

    @Test
    @DisplayName("Las operaciones que §8.1 marca 'Requiere Lock' son exactamente estas")
    void lasQueExigenLock() {
        Set<OperationType> conLock = EnumSet.allOf(OperationType.class).stream()
                .filter(OperationType::requiereLock)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(OperationType.class)));

        assertThat(conLock).containsExactlyInAnyOrder(
                OperationType.RENAME_CLASS,
                OperationType.DELETE_CLASS,
                OperationType.ADD_ATTRIBUTE,
                OperationType.UPDATE_ATTRIBUTE,
                OperationType.DELETE_ATTRIBUTE,
                OperationType.ADD_METHOD,
                OperationType.UPDATE_METHOD,
                OperationType.DELETE_METHOD,
                OperationType.DELETE_RELATIONSHIP,
                OperationType.UPDATE_RELATIONSHIP,
                OperationType.UPDATE_PACKAGE,
                OperationType.DELETE_PACKAGE);
    }

    @Test
    @DisplayName("Las tres operaciones de arrastre son LWW libre, sin lock")
    void lasDeArrastreSonLibres() {
        // Pedir lock a 60 mensajes por segundo destruiría la fluidez del arrastre, que es
        // justamente lo que §8.1 protege marcándolas 'LWW Libre'.
        assertThat(OperationType.MOVE_CLASS.exclusion()).isEqualTo(Exclusion.LWW_LIBRE);
        assertThat(OperationType.RESIZE_CLASS.exclusion()).isEqualTo(Exclusion.LWW_LIBRE);
        assertThat(OperationType.UPDATE_WAYPOINTS.exclusion()).isEqualTo(Exclusion.LWW_LIBRE);
        assertThat(OperationType.MOVE_CLASS.requiereLock()).isFalse();
    }

    @Test
    @DisplayName("Agregar elementos nuevos no requiere lock: no hay nada previo que proteger")
    void agregarNoRequiereLock() {
        assertThat(OperationType.ADD_CLASS.exclusion()).isEqualTo(Exclusion.NO_REQUIERE);
        assertThat(OperationType.ADD_RELATIONSHIP.exclusion()).isEqualTo(Exclusion.NO_REQUIERE);
        assertThat(OperationType.ADD_PACKAGE.exclusion()).isEqualTo(Exclusion.NO_REQUIERE);
    }

    @Test
    @DisplayName("BULK_MERGE bloquea el diagrama entero, no un elemento")
    void bulkMergeBloqueaElDiagrama() {
        assertThat(OperationType.BULK_MERGE.exclusion()).isEqualTo(Exclusion.LOCK_DE_DIAGRAMA);
    }

    @Test
    @DisplayName("Un op desconocido devuelve vacío en vez de lanzar")
    void opDesconocido() {
        assertThat(OperationType.desde("BORRAR_TODO")).isEmpty();
        assertThat(OperationType.desde(null)).isEmpty();
        assertThat(OperationType.desde("  move_class  ")).contains(OperationType.MOVE_CLASS);
    }
}
