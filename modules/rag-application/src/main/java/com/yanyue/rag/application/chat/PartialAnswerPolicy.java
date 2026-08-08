package com.yanyue.rag.application.chat;

import com.yanyue.rag.domain.agent.CoverageReport;
import com.yanyue.rag.domain.agent.EvidenceItem;
import com.yanyue.rag.domain.agent.QuestionPlan;
import com.yanyue.rag.domain.agent.SubQuestion;
import com.yanyue.rag.domain.agent.SubQuestionCoverage;
import com.yanyue.rag.domain.agent.SupportedSurface;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class PartialAnswerPolicy {
    Optional<Decision> decide(
            QuestionPlan plan,
            CoverageReport coverage,
            List<EvidenceItem> evidence,
            Set<Integer> judgeFailureRounds
    ) {
        if (coverage == null || coverage.sufficient() || evidence.isEmpty() || !judgeFailureRounds.isEmpty()) {
            return Optional.empty();
        }
        var coverageByQuestion = coverageByQuestion(coverage);
        var evidenceById = evidenceById(evidence);
        var plannedIds = plan.subQuestions().stream().map(SubQuestion::id)
                .collect(java.util.stream.Collectors.toSet());
        if (coverageByQuestion == null || evidenceById == null
                || !coverageByQuestion.keySet().equals(plannedIds)) {
            return Optional.empty();
        }

        var sections = new ArrayList<Section>();
        for (var question : plan.subQuestions()) {
            var item = coverageByQuestion.get(question.id());
            if (item.hasConflict() || item.supportedSurfaces().isEmpty()
                    || !validSurfaces(question.id(), item.supportedSurfaces(), evidenceById)) {
                return Optional.empty();
            }
            sections.add(new Section(question, item.supportedSurfaces()));
        }
        var gaps = coverage.items().stream()
                .filter(item -> !item.covered())
                .flatMap(item -> item.gaps().stream())
                .filter(gap -> gap != null && !gap.isBlank())
                .distinct().limit(6).toList();
        return gaps.isEmpty() ? Optional.empty() : Optional.of(new Decision(sections, gaps));
    }

    private Map<UUID, SubQuestionCoverage> coverageByQuestion(CoverageReport coverage) {
        var result = new LinkedHashMap<UUID, SubQuestionCoverage>();
        for (var item : coverage.items()) {
            if (item == null || item.subQuestionId() == null
                    || result.putIfAbsent(item.subQuestionId(), item) != null) return null;
        }
        return result;
    }

    private Map<UUID, EvidenceItem> evidenceById(List<EvidenceItem> evidence) {
        var result = new HashMap<UUID, EvidenceItem>();
        for (var item : evidence) {
            if (item == null || item.id() == null || result.putIfAbsent(item.id(), item) != null) return null;
        }
        return result;
    }

    private boolean validSurfaces(
            UUID questionId,
            List<SupportedSurface> surfaces,
            Map<UUID, EvidenceItem> evidenceById
    ) {
        var statements = new HashSet<String>();
        for (var surface : surfaces) {
            if (surface == null || surface.statement().isBlank() || !statements.add(surface.statement())
                    || surface.evidenceIds().isEmpty()) return false;
            var evidenceIds = new HashSet<UUID>();
            for (var evidenceId : surface.evidenceIds()) {
                var item = evidenceById.get(evidenceId);
                if (item == null || !item.deepRead() || !item.subQuestionId().equals(questionId)
                        || !evidenceIds.add(evidenceId)) return false;
            }
        }
        return true;
    }

    record Decision(List<Section> sections, List<String> gaps) {
        Decision {
            sections = List.copyOf(sections);
            gaps = List.copyOf(gaps);
        }
    }

    record Section(SubQuestion question, List<SupportedSurface> surfaces) {
        Section {
            surfaces = List.copyOf(surfaces);
        }
    }
}
