package com.yanyue.rag.application.chat.deep;

import com.yanyue.rag.domain.agent.deep.AcceptedEvidence;
import com.yanyue.rag.domain.agent.deep.EvidenceRequirementLink;
import com.yanyue.rag.domain.agent.deep.ResearchPhase;
import com.yanyue.rag.domain.agent.deep.SearchMode;
import com.yanyue.rag.domain.agent.deep.TargetEffect;
import com.yanyue.rag.domain.agent.deep.GoalEvidencePool;
import com.yanyue.rag.domain.agent.deep.GoalPlan;
import com.yanyue.rag.domain.agent.deep.SearchQuery;
import com.yanyue.rag.domain.chunking.CandidateSpan;
import com.yanyue.rag.domain.chunking.CandidateSpanBuilder;
import com.yanyue.rag.domain.chunking.ParentContext;
import com.yanyue.rag.domain.port.EvidenceValidationPort;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EvidenceAcceptanceService {
    private final EvidenceValidationPort evidenceValidation;
    private final Clock clock;

    public EvidenceAcceptanceService(EvidenceValidationPort evidenceValidation, Clock clock) {
        this.evidenceValidation = evidenceValidation;
        this.clock = clock;
    }

    public List<AcceptedEvidence> accept(
            GoalPlan goal,
            ResearchPhase phase,
            List<SearchQuery> queries,
            List<ParentContext> contexts,
            List<CandidateSpan> spans,
            List<CandidateSpanReasoner.Selection> selections,
            RetrievalScope scope,
            GoalEvidencePool pool
    ) {
        var contextsByParent = contexts.stream().collect(java.util.stream.Collectors.toMap(
                ParentContext::parentChunkId, value -> value, (left, right) -> left));
        var spansById = spans.stream().collect(java.util.stream.Collectors.toMap(
                CandidateSpan::spanId, value -> value, (left, right) -> left));
        var modesByQuery = queries.stream().collect(java.util.stream.Collectors.toMap(
                SearchQuery::queryId, SearchQuery::searchMode));
        var accepted = new ArrayList<AcceptedEvidence>();
        for (var selection : selections) {
            var span = spansById.get(selection.spanId());
            if (span == null) continue;
            var context = contextsByParent.get(span.parentChunkId());
            if (context == null || span.localEnd() > context.text().length()) continue;
            var quote = context.text().substring(span.localStart(), span.localEnd());
            if (!quote.equals(span.text())) continue;
            var links = links(goal, phase, selection.requirementIds());
            if (links.isEmpty()) continue;

            var queryIds = context.queryProvenance().stream()
                    .map(value -> parseUuid(value.queryId())).filter(java.util.Objects::nonNull)
                    .filter(modesByQuery::containsKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            // RouteObservation 是来源真源；缺少真实命中归属时拒绝该证据，不推断为全部 Query 命中。
            if (queryIds.isEmpty()) continue;
            var modes = queryIds.stream().map(modesByQuery::get).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var evidence = new AcceptedEvidence(
                    stableId(goal.id(), context.documentVersionId(), span.spanId()), goal.id(), links,
                    span.spanId(), context.documentId(), context.documentVersionId(), context.parentChunkId(),
                    quote, span.sourceAnchor(), String.join(" / ", context.titlePath()), pageRange(context),
                    context.retrievalScore(), phase, queryIds, modes);
            try {
                if (!evidenceValidation.isCurrentlyValid(scope.organizationId(), scope.userId(), evidence,
                        clock.instant())) continue;
                var stored = pool.accept(evidence);
                if (stored.evidenceId().equals(evidence.evidenceId())) accepted.add(stored);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // 一条 Evidence 超出配额或真实性校验失败，不影响同批其他原文证据。
            }
        }
        return List.copyOf(accepted);
    }

    /**
     * Accept a complete parent context after a parent-level Deep Read decision.
     * The parent keeps one stable evidence identity while its SourceAnchor
     * retains every independently verifiable source block.
     */
    public List<AcceptedEvidence> acceptParent(
            GoalPlan goal,
            ResearchPhase phase,
            List<SearchQuery> queries,
            ParentContext context,
            java.util.Set<UUID> requirementIds,
            RetrievalScope scope,
            GoalEvidencePool pool
    ) {
        return acceptParent(goal, phase, queries, context, requirementIds, List.of(), scope, pool);
    }

    public List<AcceptedEvidence> acceptParent(
            GoalPlan goal,
            ResearchPhase phase,
            List<SearchQuery> queries,
            ParentContext context,
            java.util.Set<UUID> requirementIds,
            List<String> supportQuotes,
            RetrievalScope scope,
            GoalEvidencePool pool
    ) {
        if (context == null || context.text().isBlank()) return List.of();
        var links = links(goal, phase, requirementIds);
        var anchor = context.sourceMap().anchorForParent(
                context.documentVersionId(), context.text().length()).orElse(null);
        if (links.isEmpty() || anchor == null) return List.of();
        var modesByQuery = queries.stream().collect(java.util.stream.Collectors.toMap(
                SearchQuery::queryId, SearchQuery::searchMode));
        var queryIds = context.queryProvenance().stream()
                .map(value -> parseUuid(value.queryId())).filter(java.util.Objects::nonNull)
                .filter(modesByQuery::containsKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (queryIds.isEmpty()) return List.of();
        var modes = queryIds.stream().map(modesByQuery::get).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var spanId = CandidateSpanBuilder.stableSpanId(context.documentVersionId(),
                context.parentChunkId(), 0, context.text().length(),
                CandidateSpanBuilder.textHash(context.text()));
        var evidence = new AcceptedEvidence(
                stableId(goal.id(), context.documentVersionId(), spanId), goal.id(), links,
                spanId, context.documentId(), context.documentVersionId(), context.parentChunkId(),
                context.text(), anchor, String.join(" / ", context.titlePath()), pageRange(context),
                context.retrievalScore(), phase, queryIds, modes);
        try {
            if (!evidenceValidation.isCurrentlyValid(scope.organizationId(), scope.userId(), evidence,
                    clock.instant())) return List.of();
            var stored = pool.accept(evidence);
            if (!stored.evidenceId().equals(evidence.evidenceId())) return List.of();
            pool.recordSupportQuotes(stored.evidenceId(), supportQuotes);
            return List.of(stored);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return List.of();
        }
    }

    private List<EvidenceRequirementLink> links(
            GoalPlan goal,
            ResearchPhase phase,
            java.util.Set<UUID> requirementIds
    ) {
        var result = new LinkedHashMap<UUID, EvidenceRequirementLink>();
        for (var requirementId : requirementIds) {
            if (!goal.requirementIds().contains(requirementId)) continue;
            var link = phase == ResearchPhase.PRIMARY
                    ? EvidenceRequirementLink.primary(requirementId)
                    : EvidenceRequirementLink.repair(requirementId,
                            stableId(goal.id(), requirementId, "deep-repair-target"), TargetEffect.CONTRIBUTES);
            result.put(requirementId, link);
        }
        return List.copyOf(result.values());
    }

    private UUID stableId(Object first, Object second, String third) {
        return UUID.nameUUIDFromBytes((first + ":" + second + ":" + third).getBytes(StandardCharsets.UTF_8));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private String pageRange(ParentContext context) {
        var pages = context.pageRange();
        if (pages.startPage() == null) return "";
        return pages.startPage().equals(pages.endPage())
                ? pages.startPage().toString() : pages.startPage() + "-" + pages.endPage();
    }
}
