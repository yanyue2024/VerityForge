package com.yanyue.rag.application.chat;

import com.yanyue.rag.domain.port.RetrievalHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class DeepReadEvidenceSelector {
    List<Selection> select(
            List<CandidateGroup> groups,
            Set<AssignmentKey> previousAssignments,
            Set<UUID> previouslyReadChunks,
            int remainingPhysicalReads,
            int maximumAssignmentsPerTask,
            int maximumAssignmentsPerQuestion
    ) {
        if (groups.isEmpty() || maximumAssignmentsPerTask < 1 || maximumAssignmentsPerQuestion < 1) {
            return List.of();
        }
        var assignments = new HashSet<>(previousAssignments);
        var physicalChunks = new HashSet<>(previouslyReadChunks);
        var selectedVersions = new HashMap<UUID, Set<UUID>>();
        var selectedCounts = new HashMap<UUID, Integer>();
        var selections = new ArrayList<Selection>();
        int remaining = Math.max(0, remainingPhysicalReads);

        // 先覆盖不同检索任务，再补第二证据族；同一子问题的总上下文必须保持有界。
        for (int slot = 0; slot < maximumAssignmentsPerTask; slot++) {
            for (var group : groups) {
                if (selectedCounts.getOrDefault(group.subQuestionId(), 0) >= maximumAssignmentsPerQuestion) {
                    continue;
                }
                var versions = selectedVersions.computeIfAbsent(group.subQuestionId(), ignored -> new HashSet<>());
                var candidate = candidate(group, assignments, physicalChunks, remaining, versions, slot > 0);
                if (candidate == null) continue;
                var physicalRead = physicalChunks.add(candidate.chunkId());
                if (physicalRead) remaining--;
                assignments.add(new AssignmentKey(group.subQuestionId(), candidate.chunkId()));
                versions.add(candidate.documentVersionId());
                selectedCounts.merge(group.subQuestionId(), 1, Integer::sum);
                selections.add(new Selection(group.taskId(), group.subQuestionId(), candidate, physicalRead));
            }
        }
        return List.copyOf(selections);
    }

    private RetrievalHit candidate(
            CandidateGroup group,
            Set<AssignmentKey> assignments,
            Set<UUID> physicalChunks,
            int remainingPhysicalReads,
            Set<UUID> selectedVersions,
            boolean preferAnotherVersion
    ) {
        if (preferAnotherVersion) {
            var differentVersion = firstSelectable(group, assignments, physicalChunks, remainingPhysicalReads,
                    selectedVersions, true, true);
            if (differentVersion != null) return differentVersion;
            differentVersion = firstSelectable(group, assignments, physicalChunks, remainingPhysicalReads,
                    selectedVersions, true, false);
            if (differentVersion != null) return differentVersion;
            var reusable = firstSelectable(group, assignments, physicalChunks, remainingPhysicalReads,
                    selectedVersions, false, true);
            if (reusable != null) return reusable;
        }
        return firstSelectable(group, assignments, physicalChunks, remainingPhysicalReads,
                selectedVersions, false, false);
    }

    private RetrievalHit firstSelectable(
            CandidateGroup group,
            Set<AssignmentKey> assignments,
            Set<UUID> physicalChunks,
            int remainingPhysicalReads,
            Set<UUID> selectedVersions,
            boolean requireAnotherVersion,
            boolean requirePreviouslyRead
    ) {
        for (var hit : group.hits()) {
            if (assignments.contains(new AssignmentKey(group.subQuestionId(), hit.chunkId()))) continue;
            if (requireAnotherVersion && selectedVersions.contains(hit.documentVersionId())) continue;
            if (requirePreviouslyRead && !physicalChunks.contains(hit.chunkId())) continue;
            if (!physicalChunks.contains(hit.chunkId()) && remainingPhysicalReads <= 0) continue;
            return hit;
        }
        return null;
    }

    record CandidateGroup(UUID taskId, UUID subQuestionId, List<RetrievalHit> hits) {
        CandidateGroup {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    record AssignmentKey(UUID subQuestionId, UUID chunkId) {
    }

    record Selection(UUID taskId, UUID subQuestionId, RetrievalHit hit, boolean physicalRead) {
    }
}
