package com.modelcollab.diagram.dto;

import java.util.List;

/**
 * Informe de validación pre-generación (§11.2.6, CU13).
 *
 * <p>La distinción entre <b>error bloqueante</b> y <b>advertencia</b> es la razón de ser de este
 * informe. Un error bloqueante describe algo que <b>no puede existir en Java</b> —herencia
 * múltiple, una relación que apunta a una clase borrada— y por lo tanto generar sería producir un
 * ZIP que no compila. Una advertencia señala algo que el generador va a resolver por su cuenta de
 * una manera concreta —inyectar una clave primaria, elegir un lado para la FK— y que conviene que
 * el modelador sepa, pero que no impide nada.
 *
 * <p>Mezclarlas tendría una de dos consecuencias, las dos malas: o se bloquea por cosas que
 * funcionan y la herramienta se vuelve insufrible, o se deja pasar lo que no compila y la promesa
 * del "100% Compila" se rompe en la demo.
 */
public record ValidationReportDTO(
        boolean valid, List<ValidationIssue> errors, List<ValidationIssue> warnings) {

    /**
     * Un hallazgo concreto.
     *
     * <p>{@code elementId} y {@code elementName} viajan los dos: el id para que la interfaz pueda
     * resaltar el elemento en el lienzo, y el nombre para que el mensaje se pueda leer. Decirle a
     * alguien que "la clase c7f3a1b2 tiene dos padres" no le sirve de nada.
     */
    public record ValidationIssue(
            String code, String elementId, String elementName, String message) {}

    public static ValidationReportDTO de(
            List<ValidationIssue> errores, List<ValidationIssue> advertencias) {
        return new ValidationReportDTO(errores.isEmpty(), errores, advertencias);
    }
}
