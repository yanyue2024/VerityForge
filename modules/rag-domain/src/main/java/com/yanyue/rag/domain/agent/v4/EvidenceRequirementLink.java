package com.yanyue.rag.domain.agent.v4;

import java.util.UUID;

public record EvidenceRequirementLink(
        UUID requirementId,
        ResearchPhase acceptedPhase,
        UUID repairTargetId,
        TargetEffect targetEffect,
        EvidenceLinkStatus status
) {
    public EvidenceRequirementLink {
        V4Validation.required(requirementId, "requirementId");
        V4Validation.required(acceptedPhase, "acceptedPhase");
        V4Validation.required(status, "status");
        if (acceptedPhase == ResearchPhase.PRIMARY && (repairTargetId != null || targetEffect != null)) {
            throw new IllegalArgumentException("primary evidence cannot reference a repair target");
        }
        if (acceptedPhase == ResearchPhase.REPAIR && (repairTargetId == null || targetEffect == null)) {
            throw new IllegalArgumentException("repair evidence must reference a repair target and effect");
        }
    }

    public static EvidenceRequirementLink primary(UUID requirementId) {
        return new EvidenceRequirementLink(requirementId, ResearchPhase.PRIMARY, null, null,
                EvidenceLinkStatus.ACTIVE);
    }

    public static EvidenceRequirementLink repair(UUID requirementId, UUID repairTargetId, TargetEffect effect) {
        return new EvidenceRequirementLink(requirementId, ResearchPhase.REPAIR, repairTargetId, effect,
                EvidenceLinkStatus.ACTIVE);
    }

    public EvidenceRequirementLink supersede() {
        return new EvidenceRequirementLink(requirementId, acceptedPhase, repairTargetId, targetEffect,
                EvidenceLinkStatus.SUPERSEDED);
    }
}
