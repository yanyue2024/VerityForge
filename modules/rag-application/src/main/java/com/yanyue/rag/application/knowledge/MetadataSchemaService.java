package com.yanyue.rag.application.knowledge;

import com.yanyue.rag.contract.chat.FilterOperator;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.knowledge.MetadataFieldRequest;
import com.yanyue.rag.contract.knowledge.MetadataFieldType;
import com.yanyue.rag.contract.knowledge.MetadataSchemaView;
import com.yanyue.rag.contract.knowledge.UpdateMetadataSchemaRequest;
import com.yanyue.rag.domain.knowledge.MetadataField;
import com.yanyue.rag.domain.knowledge.MetadataSchema;
import com.yanyue.rag.domain.knowledge.MetadataValueType;
import com.yanyue.rag.domain.port.MetadataSchemaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetadataSchemaService {
    private static final Map<String, MetadataFieldType> EXPLICIT_FILTERS = Map.of(
            "source_type", MetadataFieldType.TEXT,
            "owner", MetadataFieldType.TEXT,
            "business_domain", MetadataFieldType.TEXT,
            "tags", MetadataFieldType.TEXT_LIST
    );

    private final MetadataSchemaRepository repository;

    public MetadataSchemaService(MetadataSchemaRepository repository) {
        this.repository = repository;
    }

    public Optional<MetadataSchemaView> active(UUID organizationId, UUID knowledgeBaseId) {
        return repository.findActive(organizationId, knowledgeBaseId).map(this::view);
    }

    public List<MetadataSchemaView> history(UUID organizationId, UUID knowledgeBaseId) {
        return repository.findAll(organizationId, knowledgeBaseId).stream().map(this::view).toList();
    }

    public Optional<MetadataSchemaView> organizationActive(UUID organizationId) {
        return repository.findActiveForOrganization(organizationId).map(this::view);
    }

    public List<MetadataSchemaView> organizationHistory(UUID organizationId) {
        return repository.findAllForOrganization(organizationId).stream().map(this::view).toList();
    }

    @Transactional
    public MetadataSchemaView activateForOrganization(UUID organizationId, UpdateMetadataSchemaRequest request) {
        var fields = request.fields().stream().map(this::domain).toList();
        return view(repository.activateForOrganization(organizationId, fields));
    }

    @Transactional
    public void inheritOrganizationSchema(UUID organizationId, UUID knowledgeBaseId) {
        repository.inheritOrganizationSchema(organizationId, knowledgeBaseId);
    }

    @Transactional
    public MetadataSchemaView activate(
            UUID organizationId,
            UUID knowledgeBaseId,
            UpdateMetadataSchemaRequest request
    ) {
        var fields = request.fields().stream().map(this::domain).toList();
        var nextVersion = repository.findAll(organizationId, knowledgeBaseId).stream()
                .mapToInt(value -> value.schema().version()).max().orElse(0) + 1;
        return view(repository.activate(organizationId, knowledgeBaseId,
                new MetadataSchema(knowledgeBaseId, nextVersion, fields)));
    }

    public boolean deactivate(UUID organizationId, UUID knowledgeBaseId) {
        return repository.deactivate(organizationId, knowledgeBaseId);
    }

    public void validateMetadata(UUID organizationId, UUID knowledgeBaseId, Map<String, Object> metadata) {
        var stored = repository.findActive(organizationId, knowledgeBaseId);
        if (stored.isEmpty()) return;
        var fields = stored.get().schema().fields();
        var known = fields.stream().map(MetadataField::key).collect(java.util.stream.Collectors.toSet());
        var unknown = new HashSet<>(metadata.keySet());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown metadata fields: " + unknown);
        for (var field : fields) {
            var value = metadata.get(field.key());
            if (value == null) {
                if (field.required()) throw new IllegalArgumentException("Required metadata field is missing: " + field.key());
                continue;
            }
            validateValue(field, value);
        }
    }

    public List<MetadataFilter> validateFilters(
            UUID organizationId,
            List<UUID> knowledgeBaseIds,
            List<MetadataFilter> filters
    ) {
        if (filters.isEmpty()) return List.of();
        var enriched = new java.util.ArrayList<MetadataFilter>();
        if (knowledgeBaseIds.isEmpty()) {
            var organizationSchema = repository.findActiveForOrganization(organizationId).orElse(null);
            for (var filter : filters) {
                var type = EXPLICIT_FILTERS.get(filter.field());
                if (type != null) {
                    validateFilterOperator(MetadataValueType.valueOf(type.name()), filter.operator());
                    enriched.add(new MetadataFilter(filter.field(), filter.operator(), filter.value(), type));
                    continue;
                }
                var definition = organizationSchema == null ? null : organizationSchema.schema().fields().stream()
                        .filter(field -> field.key().equals(filter.field())).findFirst().orElse(null);
                if (definition == null || !definition.filterable()) {
                    throw new IllegalArgumentException("Metadata field is not filterable: " + filter.field());
                }
                validateFilterOperator(definition.type(), filter.operator());
                validateFilterValue(definition, filter.operator(), filter.value());
                enriched.add(new MetadataFilter(filter.field(), filter.operator(), filter.value(),
                        MetadataFieldType.valueOf(definition.type().name())));
            }
            return List.copyOf(enriched);
        }
        var schemas = knowledgeBaseIds.stream()
                .map(id -> repository.findActive(organizationId, id)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Metadata filters require an active Schema for knowledge base " + id)))
                .toList();
        for (var filter : filters) {
            var explicitType = EXPLICIT_FILTERS.get(filter.field());
            if (explicitType != null) {
                validateFilterOperator(MetadataValueType.valueOf(explicitType.name()), filter.operator());
                enriched.add(new MetadataFilter(filter.field(), filter.operator(), filter.value(), explicitType));
                continue;
            }
            var definitions = schemas.stream().map(schema -> schema.schema().fields().stream()
                            .filter(field -> field.key().equals(filter.field())).findFirst().orElse(null))
                    .toList();
            if (definitions.stream().anyMatch(java.util.Objects::isNull)
                    || definitions.stream().anyMatch(field -> !field.filterable())) {
                throw new IllegalArgumentException("Metadata field is not filterable in the selected scope: " + filter.field());
            }
            var firstType = definitions.getFirst().type();
            if (definitions.stream().anyMatch(field -> field.type() != firstType)) {
                throw new IllegalArgumentException("Metadata field has incompatible types across knowledge bases: " + filter.field());
            }
            validateFilterOperator(firstType, filter.operator());
            if (filter.operator() == FilterOperator.IN && firstType != MetadataValueType.TEXT_LIST) {
                if (!(filter.value() instanceof Collection<?> values) || values.isEmpty()) {
                    throw new IllegalArgumentException("IN filter requires a non-empty value list");
                }
                values.forEach(value -> validateValue(definitions.getFirst(), value));
            } else {
                validateFilterValue(definitions.getFirst(), filter.operator(), filter.value());
            }
            enriched.add(new MetadataFilter(filter.field(), filter.operator(), filter.value(),
                    MetadataFieldType.valueOf(firstType.name())));
        }
        return List.copyOf(enriched);
    }

    private void validateFilterOperator(MetadataValueType type, FilterOperator operator) {
        if (operator == null) throw new IllegalArgumentException("Metadata filter operator is required");
        var allowed = switch (type) {
            case BOOLEAN -> Set.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN);
            case TEXT_LIST -> Set.of(FilterOperator.EQ, FilterOperator.IN, FilterOperator.CONTAINS);
            case NUMBER, DATE, DATETIME -> Set.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN,
                    FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE);
            case TEXT -> Set.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN, FilterOperator.CONTAINS);
        };
        if (!allowed.contains(operator)) throw new IllegalArgumentException("Unsupported operator for " + type + ": " + operator);
    }

    private void validateFilterValue(MetadataField field, FilterOperator operator, Object value) {
        if (field.type() == MetadataValueType.TEXT_LIST
                && (operator == FilterOperator.EQ || operator == FilterOperator.CONTAINS)) {
            if (value instanceof Collection<?> collection) {
                if (collection.isEmpty() || collection.stream().anyMatch(item -> !(item instanceof String))) {
                    throw new IllegalArgumentException("Metadata field " + field.key() + " must contain text values");
                }
                return;
            }
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("Metadata field " + field.key() + " must be text");
            }
            return;
        }
        validateValue(field, value);
    }

    private void validateValue(MetadataField field, Object value) {
        try {
            switch (field.type()) {
                case TEXT -> require(value instanceof String, field, "text");
                case NUMBER -> require(value instanceof Number, field, "number");
                case BOOLEAN -> require(value instanceof Boolean, field, "boolean");
                case DATE -> {
                    require(value instanceof String, field, "ISO date");
                    LocalDate.parse((String) value);
                }
                case DATETIME -> {
                    require(value instanceof String, field, "ISO datetime");
                    parseInstant((String) value);
                }
                case TEXT_LIST -> require(value instanceof Collection<?> collection
                        && collection.stream().allMatch(String.class::isInstance), field, "text list");
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("Metadata field has an invalid date value: " + field.key());
        }
        if (!field.allowedValues().isEmpty()) {
            var values = value instanceof Collection<?> collection ? collection : List.of(value);
            if (values.stream().map(String::valueOf).anyMatch(item -> !field.allowedValues().contains(item))) {
                throw new IllegalArgumentException("Metadata field contains a value outside allowedValues: " + field.key());
            }
        }
    }

    private void require(boolean valid, MetadataField field, String expected) {
        if (!valid) throw new IllegalArgumentException("Metadata field " + field.key() + " must be " + expected);
    }

    private void parseInstant(String value) {
        try {
            Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            OffsetDateTime.parse(value).toInstant();
        }
    }

    private MetadataField domain(MetadataFieldRequest field) {
        return new MetadataField(field.key(), field.label(), MetadataValueType.valueOf(field.type().name()),
                field.required(), field.filterable(), field.allowedValues());
    }

    private MetadataSchemaView view(MetadataSchemaRepository.StoredMetadataSchema stored) {
        return new MetadataSchemaView(
                stored.id(), stored.schema().knowledgeBaseId(), stored.schema().version(),
                stored.schema().fields().stream().map(field -> new MetadataFieldRequest(
                        field.key(), field.label(), MetadataFieldType.valueOf(field.type().name()),
                        field.required(), field.filterable(), field.allowedValues())).toList(),
                stored.active(), stored.createdAt()
        );
    }
}
