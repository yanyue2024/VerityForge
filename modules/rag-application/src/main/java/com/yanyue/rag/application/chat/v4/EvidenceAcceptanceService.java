package com.yanyue.rag.application.chat.v4;

import com.yanyue.rag.domain.agent.v4.AcceptedEvidence;
import com.yanyue.rag.domain.agent.v4.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.v4.GoalEvidencePool;
import com.yanyue.rag.domain.agent.v4.GoalPlan;
import com.yanyue.rag.domain.agent.v4.RepairCompletionMode;
import com.yanyue.rag.domain.agent.v4.RepairTarget;
import com.yanyue.rag.domain.agent.v4.ResearchPhase;
import com.yanyue.rag.domain.agent.v4.SearchMode;
import com.yanyue.rag.domain.agent.v4.SearchQuery;
import com.yanyue.rag.domain.agent.v4.TargetEffect;
import com.yanyue.rag.domain.chunking.v4.CandidateSpan;
import com.yanyue.rag.domain.chunking.v4.ParentContext;
import com.yanyue.rag.domain.port.AgenticV4EvidenceValidationPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EvidenceAcceptanceService {
    private final AgenticV4EvidenceValidationPort evidenceValidation;
    private final Clock clock;

    public EvidenceAcceptanceService(AgenticV4EvidenceValidationPort evidenceValidation, Clock clock) {
        this.evidenceValidation = evidenceValidation;
        this.clock = clock;
    }

    public List<AcceptedEvidence> accept(
            GoalPlan goal,
            ResearchPhase phase,
            List<RepairTarget> repairTargets,
            List<SearchQuery> queries,
            List<ParentContext> contexts,
            List<CandidateSpan> spans,
            List<DeepReadReasoner.Selection> selections,
            RetrievalScope scope,
            GoalEvidencePool pool
    ) {
        var contextByParent = contexts.stream().collect(java.util.stream.Collectors.toMap(
                ParentContext::parentChunkId, value -> value, (left, right) -> left));
        var spanById = spans.stream().collect(java.util.stream.Collectors.toMap(
                CandidateSpan::spanId, value -> value, (left, right) -> left));
        var targets = repairTargets.stream().collect(java.util.stream.Collectors.toMap(
                RepairTarget::id, value -> value));
        var modesByQuery = queries.stream().collect(java.util.stream.Collectors.toMap(
                SearchQuery::queryId, SearchQuery::searchMode));
        var ordered = selections.stream().sorted(Comparator
                .comparingInt((DeepReadReasoner.Selection selection) -> priority(selection, targets))
                .thenComparing(selection -> selection.spanId())).toList();
        var accepted = new ArrayList<AcceptedEvidence>();
        for (var selection : ordered) {
            var span = spanById.get(selection.spanId());
            if (span == null) continue;
            var context = contextByParent.get(span.parentChunkId());
            if (context == null || span.localEnd() > context.text().length()) continue;
            var quote = context.text().substring(span.localStart(), span.localEnd());
            if (!quote.equals(span.text())) continue;
            var links = validLinks(goal, phase, selection.supports(), targets);
            if (links.isEmpty()) continue;
            var queryIds = context.queryProvenance().stream().map(value -> UUID.fromString(value.queryId()))
                    .filter(modesByQuery::containsKey).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (queryIds.isEmpty()) queryIds.addAll(modesByQuery.keySet());
            var retrievalModes = queryIds.stream().map(modesByQuery::get).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (retrievalModes.isEmpty()) continue;
            var evidence = new AcceptedEvidence(
                    stableEvidenceId(goal.id(), context.documentVersionId(), span.spanId()), goal.id(), links,
                    span.spanId(), context.documentId(), context.documentVersionId(), context.parentChunkId(), quote,
                    span.sourceAnchor(), String.join(" / ", context.titlePath()), pageRange(context),
                    context.retrievalScore(), phase, queryIds, retrievalModes);
            try {
                if (!evidenceValidation.isCurrentlyValid(
                        scope.organizationId(), scope.userId(), evidence, clock.instant())) continue;
                var stored = pool.accept(evidence);
                if (stored.evidenceId().equals(evidence.evidenceId())) accepted.add(stored);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // 单条选择失败不能拒绝同一次 Deep Read 的其他合法证据。
            }
        }
        return List.copyOf(accepted);
    }

    private List<EvidenceRequirementLink> validLinks(
            GoalPlan goal,
            ResearchPhase phase,
            List<DeepReadReasoner.Support> supports,
            Map<UUID, RepairTarget> targets
    ) {
        var result = new LinkedHashMap<UUID, EvidenceRequirementLink>();
        for (var support : supports) {
            if (!goal.requirementIds().contains(support.requirementId())) continue;
            if (phase == ResearchPhase.PRIMARY) {
                if (support.repairTargetId() == null && support.targetEffect() == null) {
                    result.putIfAbsent(support.requirementId(), EvidenceRequirementLink.primary(support.requirementId()));
                }
                continue;
            }
            var target = targets.get(support.repairTargetId());
            if (target == null || !target.goalId().equals(goal.id())
                    || !target.requirementId().equals(support.requirementId()) || support.targetEffect() == null) continue;
            if (target.completionMode() == RepairCompletionMode.REVIEW_REQUIRED
                    && support.targetEffect() == TargetEffect.COMPLETE) continue;
            result.putIfAbsent(support.requirementId(), EvidenceRequirementLink.repair(
                    support.requirementId(), target.id(), support.targetEffect()));
        }
        return List.copyOf(result.values());
    }

    private int priority(DeepReadReasoner.Selection selection, Map<UUID, RepairTarget> targets) {
        return selection.supports().stream().anyMatch(support -> support.targetEffect() == TargetEffect.COMPLETE
                && targets.containsKey(support.repairTargetId())) ? 0 : 1;
    }

    private UUID stableEvidenceId(UUID goalId, UUID versionId, String spanId) {
        return UUID.nameUUIDFromBytes((goalId + ":" + versionId + ":" + spanId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private String pageRange(ParentContext context) {
        var pages = context.pageRange();
        if (pages.startPage() == null) return "";
        return pages.startPage().equals(pages.endPage())
                ? pages.startPage().toString() : pages.startPage() + "-" + pages.endPage();
    }
}
