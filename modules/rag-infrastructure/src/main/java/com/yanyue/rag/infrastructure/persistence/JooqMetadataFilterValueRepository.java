package com.yanyue.rag.infrastructure.persistence;

import com.yanyue.rag.domain.knowledge.MetadataField;
import com.yanyue.rag.domain.knowledge.MetadataValueType;
import com.yanyue.rag.domain.port.MetadataFilterValueRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqMetadataFilterValueRepository implements MetadataFilterValueRepository {
    private static final int MAX_ENUMERATED_VALUES = 100;

    private final DSLContext dsl;

    public JooqMetadataFilterValueRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public FieldValues values(
            UUID organizationId,
            UUID userId,
            List<UUID> knowledgeBaseIds,
            MetadataField field
    ) {
        var scope = scope(organizationId, userId, knowledgeBaseIds);
        var populated = populated(scope, field);
        if (!populated || field.type() == MetadataValueType.DATE
                || field.type() == MetadataValueType.DATETIME
                || field.type() == MetadataValueType.NUMBER) {
            return new FieldValues(populated, List.of());
        }

        var parameters = new ArrayList<Object>();
        String sql;
        if (field.type() == MetadataValueType.TEXT_LIST) {
            parameters.add(field.key());
            parameters.add(field.key());
            parameters.addAll(scope.parameters());
            parameters.add(MAX_ENUMERATED_VALUES + 1);
            sql = """
                    SELECT DISTINCT item.value AS value
                    FROM document d
                    JOIN document_version dv ON dv.id = d.current_version_id
                    CROSS JOIN LATERAL jsonb_array_elements_text(
                        CASE WHEN jsonb_typeof(dv.metadata -> ?) = 'array'
                             THEN dv.metadata -> ? ELSE '[]'::jsonb END
                    ) item(value)
                    WHERE %s
                      AND NULLIF(btrim(item.value), '') IS NOT NULL
                    ORDER BY value
                    LIMIT ?
                    """.formatted(scope.predicate());
        } else {
            parameters.add(field.key());
            parameters.addAll(scope.parameters());
            parameters.add(field.key());
            parameters.add(MAX_ENUMERATED_VALUES + 1);
            sql = """
                    SELECT DISTINCT dv.metadata ->> ? AS value
                    FROM document d
                    JOIN document_version dv ON dv.id = d.current_version_id
                    WHERE %s
                      AND NULLIF(btrim(dv.metadata ->> ?), '') IS NOT NULL
                    ORDER BY value
                    LIMIT ?
                    """.formatted(scope.predicate());
        }
        var records = dsl.fetch(sql, parameters.toArray());
        if (records.size() > MAX_ENUMERATED_VALUES) {
            return new FieldValues(true, List.of());
        }
        return new FieldValues(true, records.getValues("value", String.class));
    }

    private boolean populated(Scope scope, MetadataField field) {
        var parameters = new ArrayList<Object>(scope.parameters());
        String predicate;
        if (field.type() == MetadataValueType.TEXT_LIST) {
            predicate = "jsonb_typeof(dv.metadata -> ?) = 'array' AND jsonb_array_length(dv.metadata -> ?) > 0";
            parameters.add(field.key());
            parameters.add(field.key());
        } else {
            predicate = "NULLIF(btrim(dv.metadata ->> ?), '') IS NOT NULL";
            parameters.add(field.key());
        }
        var record = dsl.fetchOne("""
                SELECT EXISTS (
                    SELECT 1
                    FROM document d
                    JOIN document_version dv ON dv.id = d.current_version_id
                    WHERE %s AND %s
                )
                """.formatted(scope.predicate(), predicate), parameters.toArray());
        return record != null && Boolean.TRUE.equals(record.get(0, Boolean.class));
    }

    private Scope scope(UUID organizationId, UUID userId, List<UUID> knowledgeBaseIds) {
        var predicate = new StringBuilder("""
                d.organization_id = ?
                AND d.status = 'ACTIVE'
                AND dv.status = 'PUBLISHED'
                AND (dv.valid_from IS NULL OR dv.valid_from <= now())
                AND (dv.valid_to IS NULL OR dv.valid_to > now())
                AND document_is_accessible(d.id, ?)
                """);
        var parameters = new ArrayList<Object>();
        parameters.add(organizationId);
        parameters.add(userId);
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            predicate.append(" AND d.knowledge_base_id IN (")
                    .append(String.join(",", Collections.nCopies(knowledgeBaseIds.size(), "?")))
                    .append(')');
            parameters.addAll(knowledgeBaseIds);
        }
        return new Scope(predicate.toString(), List.copyOf(parameters));
    }

    private record Scope(String predicate, List<Object> parameters) {
    }
}
