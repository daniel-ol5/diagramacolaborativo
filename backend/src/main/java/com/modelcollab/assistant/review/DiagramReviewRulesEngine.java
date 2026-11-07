package com.modelcollab.assistant.review;

import com.modelcollab.diagram.dto.ValidationReportDTO;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.service.MetamodelValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Motor de reglas deterministas para la revisión de diagramas (CU22).
 *
 * <p>Aplica primero las validaciones existentes de {@link MetamodelValidator} (errores y
 * advertencias) y agrega reglas de sugerencia de diseño orientado a objetos. Calcula un
 * puntaje objetivo e inmutable (0..100) según la fórmula acordada.
 */
@Component
public class DiagramReviewRulesEngine {

    public static final String ISOLATED_CLASS = "ISOLATED_CLASS";
    public static final String NAMING_CONVENTION = "NAMING_CONVENTION";
    public static final String GOD_CLASS = "GOD_CLASS";
    public static final String CLASS_WITHOUT_ATTRIBUTES = "CLASS_WITHOUT_ATTRIBUTES";

    private final MetamodelValidator metamodelValidator;

    public DiagramReviewRulesEngine(MetamodelValidator metamodelValidator) {
        this.metamodelValidator = metamodelValidator;
    }

    public record AnalysisResult(
            int score,
            int totalErrors,
            int totalWarnings,
            int totalSuggestions,
            List<ReviewIssue> issues) {}

    public AnalysisResult analyze(CanonicalModel model) {
        List<ReviewIssue> issues = new ArrayList<>();

        if (model == null || model.classes() == null || model.classes().isEmpty()) {
            issues.add(new ReviewIssue(
                    ReviewIssueSeverity.WARNING,
                    "EMPTY_DIAGRAM",
                    null,
                    "Diagrama",
                    "El diagrama está vacío. Agrega clases y relaciones para comenzar a diseñar."));
            return new AnalysisResult(100, 0, 1, 0, issues);
        }

        // 1. Validaciones existentes (errores bloqueantes y advertencias de metamodelo)
        ValidationReportDTO report = metamodelValidator.validar(model);
        if (report.errors() != null) {
            for (ValidationReportDTO.ValidationIssue err : report.errors()) {
                issues.add(new ReviewIssue(
                        ReviewIssueSeverity.ERROR,
                        err.code(),
                        err.elementId(),
                        err.elementName(),
                        err.message()));
            }
        }

        Set<String> classesWithEmptyAttrWarning = new HashSet<>();
        if (report.warnings() != null) {
            for (ValidationReportDTO.ValidationIssue warn : report.warnings()) {
                issues.add(new ReviewIssue(
                        ReviewIssueSeverity.WARNING,
                        warn.code(),
                        warn.elementId(),
                        warn.elementName(),
                        warn.message()));
                if (MetamodelValidator.CLASE_SIN_ATRIBUTOS.equals(warn.code())) {
                    classesWithEmptyAttrWarning.add(warn.elementId());
                }
            }
        }

        // 2. Reglas adicionales de sugerencia (buenas prácticas de POO y diseño)
        Set<String> connectedClassIds = new HashSet<>();
        if (model.relationships() != null) {
            for (var rel : model.relationships()) {
                if (rel.sourceClassId() != null) connectedClassIds.add(rel.sourceClassId());
                if (rel.targetClassId() != null) connectedClassIds.add(rel.targetClassId());
            }
        }

        for (ClassEntity clase : model.classes()) {
            if (clase == null) continue;

            // 2.1 Clase sin atributos (si no fue advertida previamente por metamodelValidator)
            if ((clase.attributes() == null || clase.attributes().isEmpty())
                    && !classesWithEmptyAttrWarning.contains(clase.id())) {
                issues.add(new ReviewIssue(
                        ReviewIssueSeverity.SUGGESTION,
                        CLASS_WITHOUT_ATTRIBUTES,
                        clase.id(),
                        clase.name(),
                        "La clase '" + clase.name() + "' no tiene atributos definidos."));
            }

            // 2.2 Clase aislada (si hay más de 1 clase y no participa en relaciones)
            if (model.classes().size() > 1 && !connectedClassIds.contains(clase.id())) {
                issues.add(new ReviewIssue(
                        ReviewIssueSeverity.SUGGESTION,
                        ISOLATED_CLASS,
                        clase.id(),
                        clase.name(),
                        "La clase '" + clase.name() + "' está aislada: no tiene relaciones con ninguna otra clase."));
            }

            // 2.3 Convenciones de nomenclatura
            if (clase.name() != null && !clase.name().isBlank()) {
                char firstChar = clase.name().charAt(0);
                if (!Character.isUpperCase(firstChar) || clase.name().contains(" ") || clase.name().contains("_")) {
                    issues.add(new ReviewIssue(
                            ReviewIssueSeverity.SUGGESTION,
                            NAMING_CONVENTION,
                            clase.id(),
                            clase.name(),
                            "El nombre de la clase '" + clase.name() + "' debería seguir PascalCase (mayúscula inicial, sin espacios ni guiones)."));
                }
            }

            if (clase.attributes() != null) {
                for (var attr : clase.attributes()) {
                    if (attr.name() != null && !attr.name().isBlank()) {
                        char first = attr.name().charAt(0);
                        if (!Character.isLowerCase(first) || attr.name().contains(" ") || attr.name().contains("_")) {
                            issues.add(new ReviewIssue(
                                    ReviewIssueSeverity.SUGGESTION,
                                    NAMING_CONVENTION,
                                    clase.id(),
                                    clase.name(),
                                    "El atributo '" + attr.name() + "' en la clase '" + clase.name() + "' debería seguir camelCase (minúscula inicial)."));
                        }
                    }
                }
            }

            // 2.4 Clase con demasiados miembros (God Class)
            int membersCount = (clase.attributes() != null ? clase.attributes().size() : 0)
                    + (clase.methods() != null ? clase.methods().size() : 0);
            if (membersCount > 15) {
                issues.add(new ReviewIssue(
                        ReviewIssueSeverity.SUGGESTION,
                        GOD_CLASS,
                        clase.id(),
                        clase.name(),
                        "La clase '" + clase.name() + "' tiene " + membersCount + " miembros. Considera dividirla para respetar el Principio de Responsabilidad Única (SRP)."));
            }
        }

        int errors = (int) issues.stream().filter(i -> i.severity() == ReviewIssueSeverity.ERROR).count();
        int warnings = (int) issues.stream().filter(i -> i.severity() == ReviewIssueSeverity.WARNING).count();
        int suggestions = (int) issues.stream().filter(i -> i.severity() == ReviewIssueSeverity.SUGGESTION).count();

        int score = calculateScore(errors, warnings, suggestions);

        return new AnalysisResult(score, errors, warnings, suggestions, List.copyOf(issues));
    }

    public static int calculateScore(int errors, int warnings, int suggestions) {
        int base = 100 - (errors * 25) - (warnings * 10) - (suggestions * 5);
        if (errors > 0 && base > 50) {
            base = 50; // Con errores bloqueantes el diseño no puede tener calificación aprobatoria
        }
        return Math.max(0, Math.min(100, base));
    }
}
