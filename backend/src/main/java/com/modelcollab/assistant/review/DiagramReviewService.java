package com.modelcollab.assistant.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelcollab.assistant.ai.AiChatModelClient;
import com.modelcollab.assistant.ai.ChatRequest;
import com.modelcollab.assistant.ai.ChatTurn;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatMessageKind;
import com.modelcollab.diagram.model.Diagram;
import com.modelcollab.diagram.repository.DiagramRepository;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.project.repository.ProjectMemberRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Servicio para solicitar la revisión arquitectónica del diagrama con IA (CU22).
 *
 * <p>Evalúa las reglas sobre el estado canónico del diagrama, calcula el puntaje (0..100),
 * solicita a la IA un comentario pedagógico sobre los 3 problemas principales y publica
 * una tarjeta con el resultado estructurado en el chat del proyecto.
 */
@Service
public class DiagramReviewService {

    private static final Logger log = LoggerFactory.getLogger(DiagramReviewService.class);

    private final DiagramRepository diagramRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final DiagramReviewRulesEngine rulesEngine;
    private final ObjectMapper canonicalObjectMapper;
    private final AiChatModelClient aiClient;
    private final ChatService chatService;

    public DiagramReviewService(
            DiagramRepository diagramRepository,
            ProjectMemberRepository projectMemberRepository,
            DiagramReviewRulesEngine rulesEngine,
            ObjectMapper canonicalObjectMapper,
            AiChatModelClient aiClient,
            ChatService chatService) {
        this.diagramRepository = diagramRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.rulesEngine = rulesEngine;
        this.canonicalObjectMapper = canonicalObjectMapper;
        this.aiClient = aiClient;
        this.chatService = chatService;
    }

    public DiagramReviewResponse reviewDiagram(UUID diagramId, UUID userId) {
        if (diagramId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DiagramId y userId son requeridos");
        }

        Diagram diagram = diagramRepository.findById(diagramId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "El diagrama no existe"));

        if (projectMemberRepository.findRoleByDiagramAndUser(diagramId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro del proyecto de este diagrama");
        }

        CanonicalModel model;
        try {
            model = canonicalObjectMapper.readValue(diagram.getCurrentState(), CanonicalModel.class);
        } catch (JsonProcessingException e) {
            log.error("Error al deserializar current_state del diagrama {}", diagramId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "El estado del diagrama no es válido");
        }

        DiagramReviewRulesEngine.AnalysisResult analysis = rulesEngine.analyze(model);

        // Seleccionar los 3 problemas principales para la IA (orden: ERROR > WARNING > SUGGESTION)
        List<ReviewIssue> topIssues = analysis.issues().stream()
                .sorted(Comparator.comparingInt(i -> i.severity().ordinal()))
                .limit(3)
                .toList();

        String mentorComment = generateMentorComment(analysis.score(), topIssues);

        // Publicar la tarjeta en el chat del proyecto
        UUID projectId = diagram.getProject().getId();
        publicarEnChat(projectId, diagram.getId(), diagram.getMutationVersion(), analysis, mentorComment);

        return new DiagramReviewResponse(
                analysis.score(),
                analysis.totalErrors(),
                analysis.totalWarnings(),
                analysis.totalSuggestions(),
                diagram.getMutationVersion(),
                mentorComment,
                analysis.issues());
    }

    private String generateMentorComment(int score, List<ReviewIssue> topIssues) {
        if (topIssues.isEmpty()) {
            return "¡Excelente trabajo! El modelo cumple con las convenciones arquitectónicas y de diseño. No se encontraron errores ni advertencias.";
        }

        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Eres el Mentor de Arquitectura de Software en ModelCollab.\n");
            prompt.append("El equipo solicitó una revisión del diagrama UML. El puntaje actual es ").append(score).append("/100.\n");
            prompt.append("Los problemas principales detectados son:\n");
            for (int i = 0; i < topIssues.size(); i++) {
                ReviewIssue issue = topIssues.get(i);
                prompt.append(i + 1).append(". [").append(issue.severity()).append("] ")
                        .append(issue.elementName() != null ? issue.elementName() : "Elemento")
                        .append(": ").append(issue.message()).append("\n");
            }
            prompt.append("\nInstrucciones:\n");
            prompt.append("- Proporciona un comentario breve y constructivo (2 o 3 oraciones).\n");
            prompt.append("- Explica pedagógicamente por qué corregir estos puntos mejorará la arquitectura.\n");
            prompt.append("- No inventes ni menciones ningún otro problema ajeno a los listados.\n");
            prompt.append("- Dirígete al equipo en español de forma motivadora y profesional.");

            ChatRequest request = new ChatRequest(
                    prompt.toString(),
                    List.of(ChatTurn.user("Por favor evalúa estos puntos y danos tu consejo de mentor.")),
                    0.3,
                    250,
                    "Equipo");

            var completion = aiClient.chat(request);
            if (completion != null && completion.text() != null && !completion.text().isBlank()
                    && !completion.text().contains("(respuesta simulada)")) {
                return completion.text().trim();
            }
        } catch (Exception e) {
            log.warn("No se pudo obtener comentario de la IA para la revisión (usando comentario predeterminado): {}", e.getMessage());
        }

        // Fallback pedagógico determinista
        StringBuilder fallback = new StringBuilder();
        fallback.append("Te sugiero prestar especial atención a los siguientes aspectos prioritarios: ");
        for (int i = 0; i < topIssues.size(); i++) {
            ReviewIssue issue = topIssues.get(i);
            if (i > 0) fallback.append("; ");
            fallback.append(issue.elementName() != null ? issue.elementName() + " (" + issue.message() + ")" : issue.message());
        }
        fallback.append(". Resolver estos puntos optimizará la modularidad y solidez de tu arquitectura.");
        return fallback.toString();
    }

    private void publicarEnChat(
            UUID projectId,
            UUID diagramId,
            long mutationVersion,
            DiagramReviewRulesEngine.AnalysisResult analysis,
            String mentorComment) {
        String textoPlano = String.format(
                "📋 Revisión del diagrama (v%d): Puntaje %d/100\n"
                        + "• Errores: %d | Advertencias: %d | Sugerencias: %d\n\n"
                        + "Comentario del Mentor:\n%s",
                mutationVersion,
                analysis.score(),
                analysis.totalErrors(),
                analysis.totalWarnings(),
                analysis.totalSuggestions(),
                mentorComment);

        List<Map<String, Object>> issuesMeta = analysis.issues().stream()
                .map(i -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("severity", i.severity().name());
                    map.put("code", i.code());
                    map.put("elementId", i.elementId() != null ? i.elementId() : "");
                    map.put("elementName", i.elementName() != null ? i.elementName() : "");
                    map.put("message", i.message());
                    return map;
                })
                .toList();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "DIAGRAM_REVIEW");
        meta.put("score", analysis.score());
        meta.put("totalErrors", analysis.totalErrors());
        meta.put("totalWarnings", analysis.totalWarnings());
        meta.put("totalSuggestions", analysis.totalSuggestions());
        meta.put("diagramVersion", mutationVersion);
        meta.put("comment", mentorComment);
        meta.put("issues", issuesMeta);

        try {
            chatService.postCard(projectId, diagramId.toString(), ChatMessageKind.REVIEW, textoPlano, meta);
        } catch (Exception e) {
            log.error("No se pudo publicar la tarjeta de revisión en el chat del proyecto {}: {}", projectId, e.getMessage(), e);
        }
    }
}

