package com.yanyue.rag.domain.agent.deep;

import com.yanyue.rag.domain.chunking.SourceAnchor;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record AcceptedEvidence(
        UUID evidenceId,
        UUID goalId,
        List<EvidenceRequirementLink> requirementLinks,
        String spanId,
        UUID documentId,
        UUID documentVersionId,
        UUID parentChunkId,
        String quote,
        SourceAnchor sourceAnchor,
        String titlePath,
        String pageRange,
        double retrievalScore,
        ResearchPhase firstAcceptedPhase,
        Set<UUID> querySourceIds,
        Set<SearchMode> retrievalSources
) {
    public AcceptedEvidence {
        DeepValidation.required(evidenceId, "evidenceId");
        DeepValidation.required(goalId, "goalId");
        requirementLinks = List.copyOf(DeepValidation.required(requirementLinks, "requirementLinks"));
        if (requirementLinks.isEmpty() || requirementLinks.stream().noneMatch(
                link -> link.status() == EvidenceLinkStatus.ACTIVE)) {
            throw new IllegalArgumentException("accepted evidence must have at least one active requirement link");
        }
        var linkedRequirements = new LinkedHashSet<UUID>();
        for (var link : requirementLinks) {
            if (!linkedRequirements.add(link.requirementId())) {
                throw new IllegalArgumentException("requirement links must be unique");
            }
        }
        spanId = DeepValidation.requiredText(spanId, "spanId");
        DeepValidation.required(documentId, "documentId");
        DeepValidation.required(documentVersionId, "documentVersionId");
        DeepValidation.required(parentChunkId, "parentChunkId");
        quote = DeepValidation.requiredText(quote, "quote");
        DeepValidation.required(sourceAnchor, "sourceAnchor");
        if (!documentVersionId.equals(sourceAnchor.documentVersionId())
                || !parentChunkId.equals(sourceAnchor.parentChunkId())) {
            throw new IllegalArgumentException("source anchor does not belong to the evidence source");
        }
        if (sourceAnchor.parentLocalEnd() - sourceAnchor.parentLocalStart() != quote.length()) {
            throw new IllegalArgumentException("quote length must match the UTF-16 source anchor range");
        }
        titlePath = titlePath == null ? "" : titlePath;
        pageRange = pageRange == null ? "" : pageRange;
        if (!Double.isFinite(retrievalScore)) {
            throw new IllegalArgumentException("retrievalScore must be finite");
        }
        DeepValidation.required(firstAcceptedPhase, "firstAcceptedPhase");
        querySourceIds = Set.copyOf(new LinkedHashSet<>(DeepValidation.required(querySourceIds, "querySourceIds")));
        retrievalSources = Set.copyOf(new LinkedHashSet<>(DeepValidation.required(retrievalSources,
                "retrievalSources")));
        if (querySourceIds.isEmpty() || retrievalSources.isEmpty()) {
            throw new IllegalArgumentException("accepted evidence must retain retrieval provenance");
        }
    }

    public Set<UUID> activeRequirementIds() {
        return requirementLinks.stream()
                .filter(link -> link.status() == EvidenceLinkStatus.ACTIVE)
                .map(EvidenceRequirementLink::requirementId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public AcceptedEvidence mergeSameSpan(AcceptedEvidence other) {
        if (!goalId.equals(other.goalId()) || !documentId.equals(other.documentId())
                || !documentVersionId.equals(other.documentVersionId()) || !parentChunkId.equals(other.parentChunkId())
                || !spanId.equals(other.spanId()) || !quote.equals(other.quote())
                || !sourceAnchor.equals(other.sourceAnchor())) {
            throw new IllegalArgumentException("same span key contains inconsistent source data");
        }
        var links = new LinkedHashMap<UUID, EvidenceRequirementLink>();
        requirementLinks.forEach(link -> links.put(link.requirementId(), link));
        other.requirementLinks().forEach(link -> links.merge(link.requirementId(), link,
                AcceptedEvidence::strongerLink));
        var queries = new LinkedHashSet<>(querySourceIds);
        queries.addAll(other.querySourceIds());
        var sources = new LinkedHashSet<>(retrievalSources);
        sources.addAll(other.retrievalSources());
        return new AcceptedEvidence(evidenceId, goalId, List.copyOf(links.values()), spanId, documentId,
                documentVersionId, parentChunkId, quote, sourceAnchor, titlePath, pageRange,
                Math.max(retrievalScore, other.retrievalScore()), firstAcceptedPhase, queries, sources);
    }

    private static EvidenceRequirementLink strongerLink(
            EvidenceRequirementLink left,
            EvidenceRequirementLink right
    ) {
        if (left.status() != right.status()) {
            return right.status() == EvidenceLinkStatus.ACTIVE ? right : left;
        }
        int leftStrength = linkStrength(left);
        int rightStrength = linkStrength(right);
        return rightStrength > leftStrength ? right : left;
    }

    private static int linkStrength(EvidenceRequirementLink link) {
        if (link.targetEffect() == TargetEffect.COMPLETE) return 3;
        if (link.targetEffect() == TargetEffect.CONTRIBUTES) return 2;
        return 1;
    }
}
