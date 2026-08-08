package com.yanyue.rag.infrastructure.retrieval;

import com.yanyue.rag.domain.port.QuestionSuggestionBenchmarkPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqQuestionSuggestionBenchmarkAdapter implements QuestionSuggestionBenchmarkPort {
    private static final String BENCHMARK_BLUEPRINT = "chinese-enterprise-agentic-retrieval-v1";
    private static final String BENCHMARK_DATASET_NAME = "中文企业技术知识库 Agentic Retrieval 困难集 v1";

    private final DSLContext dsl;

    public JooqQuestionSuggestionBenchmarkAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<BenchmarkPool> find(
            UUID organizationId,
            List<UUID> knowledgeBaseIds,
            Set<UUID> eligibleDocumentVersionIds
    ) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.size() != 1) return Optional.empty();
        var knowledgeBaseId = knowledgeBaseIds.getFirst();
        var knowledgeBaseExists = dsl.fetchExists(dsl.selectOne()
                .from("knowledge_base")
                .where("id = ? AND organization_id = ?", knowledgeBaseId, organizationId));
        if (!knowledgeBaseExists) return Optional.empty();

        var eligibleDocumentIds = new HashSet<UUID>();
        dsl.fetch("""
                SELECT id, current_version_id
                FROM document
                WHERE knowledge_base_id = ?
                  AND organization_id = ?
                  AND status = 'ACTIVE'
                """, knowledgeBaseId, organizationId).forEach(record -> {
            var versionId = record.get("current_version_id", UUID.class);
            if (versionId != null && eligibleDocumentVersionIds.contains(versionId)) {
                eligibleDocumentIds.add(record.get("id", UUID.class));
            }
        });

        var records = dsl.fetch("""
                SELECT ec.id,
                       ec.question,
                       ec.expected_document_ids,
                       ec.position,
                       ec.metadata ->> 'challengeType' AS challenge_type,
                       ec.metadata ->> 'sourceProject' AS source_project
                FROM evaluation_case ec
                JOIN evaluation_dataset ed ON ed.id = ec.dataset_id
                WHERE ed.organization_id = ?
                  AND ed.name = ?
                  AND ec.metadata ->> 'benchmarkBlueprint' = ?
                  AND ec.metadata ->> 'knowledgeBaseId' = ?
                ORDER BY ec.position, ec.id
                """, organizationId, BENCHMARK_DATASET_NAME, BENCHMARK_BLUEPRINT, knowledgeBaseId.toString());
        if (records.isEmpty()) return Optional.empty();

        var questions = new ArrayList<BenchmarkQuestion>();
        var revisionInput = new StringBuilder();
        for (var record : records) {
            var expectedDocumentIds = record.get("expected_document_ids", UUID[].class);
            if (expectedDocumentIds == null || expectedDocumentIds.length == 0) continue;
            if (!eligibleDocumentIds.containsAll(List.of(expectedDocumentIds))) continue;

            var challengeType = value(record.get("challenge_type", String.class));
            var sourceProject = value(record.get("source_project", String.class));
            var question = new BenchmarkQuestion(
                    record.get("id", UUID.class),
                    record.get("question", String.class),
                    challengeType,
                    sourceProject,
                    record.get("position", Integer.class));
            questions.add(question);
            revisionInput.append(question.id()).append('\u001f')
                    .append(question.text()).append('\u001f')
                    .append(question.position()).append('\n');
        }
        if (questions.isEmpty()) return Optional.of(new BenchmarkPool(sha256(revisionInput.toString()), List.of()));
        return Optional.of(new BenchmarkPool(sha256(revisionInput.toString()), questions));
    }

    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
