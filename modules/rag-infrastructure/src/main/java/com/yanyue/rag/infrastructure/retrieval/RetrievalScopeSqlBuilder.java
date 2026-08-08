package com.yanyue.rag.infrastructure.retrieval;

import com.yanyue.rag.contract.chat.FilterOperator;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.knowledge.MetadataFieldType;
import com.yanyue.rag.domain.retrieval.RetrievalScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class RetrievalScopeSqlBuilder {
    private static final Map<String, String> EXPLICIT_FIELDS = Map.of(
            "source_type", "dv.source_type",
            "owner", "dv.owner_name",
            "business_domain", "dv.business_domain"
    );

    public ScopedSql build(RetrievalScope scope) {
        var sql = new StringBuilder("""
                d.organization_id = ?
                AND d.status = 'ACTIVE'
                AND d.current_version_id = dv.id
                AND dv.status = 'PUBLISHED'
                AND (dv.valid_from IS NULL OR dv.valid_from <= ?::timestamptz)
                AND (dv.valid_to IS NULL OR dv.valid_to > ?::timestamptz)
                AND c.enabled = true
                AND c.chunk_type = 'CHILD'
                """);
        var parameters = new ArrayList<Object>();
        parameters.add(scope.organizationId());
        var effectiveAt = OffsetDateTime.ofInstant(scope.effectiveAt(), ZoneOffset.UTC);
        parameters.add(effectiveAt);
        parameters.add(effectiveAt);

        if (!scope.accessControlBypass()) {
            sql.append(" AND document_is_accessible(d.id, ?)");
            parameters.add(scope.userId());
        }

        appendUuidScope(sql, parameters, "d.knowledge_base_id", scope.knowledgeBaseIds());
        appendUuidScope(sql, parameters, "d.id", scope.documentIds());
        for (var filter : scope.metadataFilters()) appendFilter(sql, parameters, filter);
        sql.append('\n');
        return new ScopedSql(sql.toString(), List.copyOf(parameters));
    }

    private void appendUuidScope(StringBuilder sql, List<Object> parameters, String column, List<java.util.UUID> ids) {
        if (ids.isEmpty()) return;
        sql.append(" AND ").append(column).append(" IN (");
        sql.append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")));
        sql.append(')');
        parameters.addAll(ids);
    }

    private void appendFilter(StringBuilder sql, List<Object> parameters, MetadataFilter filter) {
        if (filter.field() == null || !filter.field().matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid metadata filter field");
        }
        var explicit = EXPLICIT_FIELDS.get(filter.field());
        if ("tags".equals(filter.field())) {
            appendTagsFilter(sql, parameters, filter);
            return;
        }
        if (explicit != null) {
            appendScalar(sql, parameters, explicit, filter.operator(), filter.value(), false, null, null);
            return;
        }
        if (filter.valueType() == MetadataFieldType.TEXT_LIST) {
            appendCustomTextList(sql, parameters, filter);
            return;
        }
        var cast = switch (filter.valueType() == null ? MetadataFieldType.TEXT : filter.valueType()) {
            case NUMBER -> "numeric";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "timestamptz";
            case TEXT, TEXT_LIST -> null;
        };
        var expression = cast == null ? "dv.metadata ->> ?" : "(dv.metadata ->> ?)::" + cast;
        appendScalar(sql, parameters, expression, filter.operator(), filter.value(), true, filter.field(), cast);
    }

    private void appendTagsFilter(StringBuilder sql, List<Object> parameters, MetadataFilter filter) {
        if (filter.operator() == FilterOperator.CONTAINS || filter.operator() == FilterOperator.EQ) {
            sql.append(" AND ? = ANY(dv.tags)");
            parameters.add(scalarText(filter.value()));
        } else if (filter.operator() == FilterOperator.IN && filter.value() instanceof Collection<?> values
                && !values.isEmpty()) {
            sql.append(" AND dv.tags && ARRAY[");
            sql.append(String.join(",", java.util.Collections.nCopies(values.size(), "?")));
            sql.append("]::text[]");
            values.forEach(value -> parameters.add(scalarText(value)));
        } else {
            throw new IllegalArgumentException("Unsupported tags filter operator: " + filter.operator());
        }
    }

    private void appendCustomTextList(StringBuilder sql, List<Object> parameters, MetadataFilter filter) {
        var values = filter.value() instanceof Collection<?> collection ? collection : List.of(filter.value());
        if (values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Text-list metadata filter requires values");
        }
        if (filter.operator() != FilterOperator.EQ && filter.operator() != FilterOperator.CONTAINS
                && filter.operator() != FilterOperator.IN) {
            throw new IllegalArgumentException("Unsupported text-list filter operator: " + filter.operator());
        }
        sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(COALESCE(dv.metadata -> ?, '[]'::jsonb)) item(value) WHERE item.value IN (")
                .append(String.join(",", java.util.Collections.nCopies(values.size(), "?")))
                .append("))");
        parameters.add(filter.field());
        values.forEach(value -> parameters.add(scalarText(value)));
    }

    private void appendScalar(StringBuilder sql, List<Object> parameters, String expression, FilterOperator operator,
                              Object value, boolean custom, String customKey, String valueCast) {
        if (operator == null || value == null) {
            throw new IllegalArgumentException("Metadata filter operator and value are required");
        }
        if (operator == FilterOperator.IN) {
            if (!(value instanceof Collection<?> values) || values.isEmpty()) {
                throw new IllegalArgumentException("IN filter requires values");
            }
            sql.append(" AND ").append(expression).append(" IN (")
                    .append(String.join(",", java.util.Collections.nCopies(values.size(),
                            valueCast == null ? "?" : "?::" + valueCast))).append(')');
            if (custom) parameters.add(customKey);
            values.forEach(item -> parameters.add(scalarText(item)));
            return;
        }
        var symbol = switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case CONTAINS -> "ILIKE";
            case IN -> throw new IllegalStateException("Handled above");
        };
        sql.append(" AND ").append(expression).append(' ').append(symbol).append(' ')
                .append(valueCast == null ? "?" : "?::" + valueCast);
        if (custom) parameters.add(customKey);
        var text = scalarText(value);
        parameters.add(operator == FilterOperator.CONTAINS ? "%" + text + "%" : text);
    }

    private String scalarText(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw new IllegalArgumentException("Metadata filter values must be scalar values");
    }
}
