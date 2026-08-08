package com.yanyue.rag.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.application.pipeline.PipelineConfigService;
import com.yanyue.rag.contract.evaluation.EvaluationJudgeMode;
import com.yanyue.rag.domain.evaluation.EvaluationCase;
import com.yanyue.rag.domain.port.EvaluationRepository.CitationEvidence;
import com.yanyue.rag.domain.port.StructuredReasoningModelPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class EvaluationJudge {
    private static final String PROMPT_VERSION = "evaluation-judge-v2";
    private static final Set<String> ANSWER_VERDICTS =
            Set.of("CORRECT", "PARTIAL", "INCORRECT", "NOT_APPLICABLE");
    private static final Set<String> CITATION_VERDICTS =
            Set.of("SUPPORTED", "PARTIAL", "UNSUPPORTED", "NOT_APPLICABLE");

    private final StructuredReasoningModelPort model;
    private final PipelineConfigService pipelineConfigs;
    private final ObjectMapper objectMapper;
    private final String prompt;

    public EvaluationJudge(
            StructuredReasoningModelPort model,
            PipelineConfigService pipelineConfigs,
            ObjectMapper objectMapper
    ) {
        this.model = model;
        this.pipelineConfigs = pipelineConfigs;
        this.objectMapper = objectMapper;
        this.prompt = resource("prompts/evaluation-judge-v2.md");
    }

    public Map<String, Object> judge(
            UUID organizationId,
            UUID requestedProfileId,
            EvaluationJudgeMode mode,
            EvaluationCase evaluationCase,
            String actualAnswer,
            List<CitationEvidence> citations
    ) {
        if (mode == EvaluationJudgeMode.NONE) return Map.of();
        boolean judgeAnswer = evaluationCase.expectedAnswer() != null
                && !evaluationCase.expectedAnswer().isBlank();
        boolean judgeCitations = mode == EvaluationJudgeMode.ANSWER_AND_CITATIONS && !citations.isEmpty();
        if (!judgeAnswer && !judgeCitations) {
            return Map.of("judgeStatus", "SKIPPED", "judgeMode", mode.name());
        }
        var config = pipelineConfigs.resolve(organizationId, requestedProfileId);
        var profileId = requestedProfileId == null ? config.chatProfileId() : requestedProfileId;
        var input = new LinkedHashMap<String, Object>();
        input.put("question", limit(evaluationCase.question(), 8_000));
        input.put("referenceAnswer", limit(evaluationCase.expectedAnswer(), 16_000));
        input.put("actualAnswer", limit(actualAnswer, 16_000));
        input.put("judgeAnswer", judgeAnswer);
        input.put("judgeCitations", judgeCitations);
        input.put("citations", citations.stream().limit(30).map(citation -> Map.of(
                "label", "E" + citation.citationIndex(),
                "documentId", citation.documentId(),
                "documentVersionId", citation.documentVersionId(),
                "quote", limit(citation.quote(), 2_000)
        )).toList());
        var userPrompt = json(input);
        var raw = model.completeJson(profileId, "evaluation-judge", prompt, userPrompt);
        Map<String, Object> result;
        try {
            result = parse(raw, judgeAnswer, judgeCitations);
        } catch (IllegalStateException invalid) {
            var repairPrompt = prompt + "\n上一次输出不符合 schema。请严格修复；错误："
                    + limit(invalid.getMessage(), 500);
            raw = model.completeJson(profileId, "evaluation-judge-repair", repairPrompt, userPrompt);
            result = parse(raw, judgeAnswer, judgeCitations);
        }
        var values = new LinkedHashMap<>(result);
        values.put("judgeStatus", "COMPLETED");
        values.put("judgeMode", mode.name());
        values.put("judgeModelProfileId", profileId);
        values.put("judgePromptVersion", PROMPT_VERSION);
        return Map.copyOf(values);
    }

    private Map<String, Object> parse(String raw, boolean judgeAnswer, boolean judgeCitations) {
        try {
            var root = objectMapper.readTree(raw);
            var values = new LinkedHashMap<String, Object>();
            parseSection(root.path("answer"), "semanticAnswer", ANSWER_VERDICTS, judgeAnswer, values);
            parseSection(root.path("citations"), "citationEntailment", CITATION_VERDICTS,
                    judgeCitations, values);
            return Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("Judge returned invalid JSON", exception);
        }
    }

    private void parseSection(
            JsonNode node,
            String prefix,
            Set<String> verdicts,
            boolean enabled,
            Map<String, Object> values
    ) {
        if (!node.isObject()) throw new IllegalStateException(prefix + " section is missing");
        var verdict = node.path("verdict").asText("");
        var score = node.path("score").asDouble(Double.NaN);
        if (!verdicts.contains(verdict) || !Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalStateException(prefix + " verdict or score is invalid");
        }
        if (enabled && "NOT_APPLICABLE".equals(verdict)) {
            throw new IllegalStateException(prefix + " cannot be NOT_APPLICABLE when enabled");
        }
        if (!enabled && !"NOT_APPLICABLE".equals(verdict)) {
            throw new IllegalStateException(prefix + " must be NOT_APPLICABLE when disabled");
        }
        values.put(prefix + "Verdict", verdict);
        values.put(prefix + "Score", score);
        values.put(prefix + "Reason", limit(node.path("reason").asText(""), 2_000));
        if ("semanticAnswer".equals(prefix)) {
            values.put("semanticMissingFacts", textList(node.path("missingFacts")));
        }
        values.put(prefix + "UnsupportedClaims", textList(node.path("unsupportedClaims")));
    }

    private List<String> textList(JsonNode node) {
        if (!node.isArray()) throw new IllegalStateException("Judge list field is invalid");
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual()) throw new IllegalStateException("Judge list item is invalid");
            if (values.size() < 30) values.add(limit(item.asText(), 1_000));
        }
        return List.copyOf(values);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize evaluation judge input", exception);
        }
    }

    private String resource(String path) {
        try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing prompt resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read prompt resource: " + path, exception);
        }
    }

    private String limit(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
