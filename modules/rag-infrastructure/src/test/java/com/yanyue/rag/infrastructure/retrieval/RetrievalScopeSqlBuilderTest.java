package com.yanyue.rag.infrastructure.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanyue.rag.contract.chat.FilterOperator;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.knowledge.MetadataFieldType;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalScopeSqlBuilderTest {
    @Test
    void terminatesDynamicMetadataPredicateAndPreservesParameterOrder() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var effectiveAt = Instant.parse("2026-07-10T12:00:00Z");
        var scope = RetrievalScope.forUser(
                organizationId,
                userId,
                List.of(knowledgeBaseId),
                List.of(),
                List.of(new MetadataFilter("release", FilterOperator.EQ, "v2")),
                effectiveAt
        );

        var sql = new RetrievalScopeSqlBuilder().build(scope);

        assertThat(sql.predicate())
                .contains("d.knowledge_base_id IN (?)")
                .contains("document_is_accessible(d.id, ?)")
                .contains("dv.metadata ->> ? = ?")
                .endsWith("\n");
        assertThat(sql.parameters())
                .containsExactly(organizationId, effectiveAt.atOffset(java.time.ZoneOffset.UTC),
                        effectiveAt.atOffset(java.time.ZoneOffset.UTC), userId, knowledgeBaseId, "release", "v2");
    }

    @Test
    void systemScopeMakesTheBypassExplicit() {
        var organizationId = UUID.randomUUID();
        var sql = new RetrievalScopeSqlBuilder().build(RetrievalScope.system(
                organizationId, List.of(), List.of(), List.of(), Instant.parse("2026-07-10T12:00:00Z")));

        assertThat(sql.predicate()).doesNotContain("document_is_accessible");
        assertThat(sql.parameters()).hasSize(3);
    }

    @Test
    void versionUsesGovernedJsonMetadataInsteadOfEmptyLegacyColumn() {
        var filter = new MetadataFilter("version", FilterOperator.EQ, "1", MetadataFieldType.TEXT);
        var sql = new RetrievalScopeSqlBuilder().build(RetrievalScope.system(
                UUID.randomUUID(), List.of(), List.of(), List.of(filter), Instant.parse("2026-07-10T12:00:00Z")));

        assertThat(sql.predicate()).contains("dv.metadata ->> ? = ?");
        assertThat(sql.predicate()).doesNotContain("dv.version_label");
        assertThat(sql.parameters()).endsWith("version", "1");
    }
}
