package com.yanyue.rag.application.knowledge;

import com.yanyue.rag.contract.knowledge.MetadataFieldType;
import com.yanyue.rag.contract.knowledge.MetadataFilterFieldView;
import com.yanyue.rag.contract.knowledge.MetadataFilterOptionsView;
import com.yanyue.rag.domain.knowledge.MetadataField;
import com.yanyue.rag.domain.port.MetadataFilterValueRepository;
import com.yanyue.rag.domain.port.MetadataSchemaRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MetadataFilterOptionsService {
    private final MetadataSchemaRepository schemas;
    private final MetadataFilterValueRepository values;

    public MetadataFilterOptionsService(
            MetadataSchemaRepository schemas,
            MetadataFilterValueRepository values
    ) {
        this.schemas = schemas;
        this.values = values;
    }

    public MetadataFilterOptionsView options(
            UUID organizationId,
            UUID userId,
            List<UUID> knowledgeBaseIds
    ) {
        var selectedIds = knowledgeBaseIds == null
                ? List.<UUID>of()
                : knowledgeBaseIds.stream().distinct().toList();
        var fields = compatibleFields(organizationId, selectedIds);
        var views = fields.stream().map(field -> {
            var available = values.values(organizationId, userId, selectedIds, field);
            return new MetadataFilterFieldView(
                    field.key(),
                    field.label(),
                    MetadataFieldType.valueOf(field.type().name()),
                    available.populated(),
                    available.values()
            );
        }).toList();
        return new MetadataFilterOptionsView(views);
    }

    private List<MetadataField> compatibleFields(UUID organizationId, List<UUID> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return schemas.findActiveForOrganization(organizationId)
                    .map(stored -> stored.schema().fields().stream()
                            .filter(MetadataField::filterable)
                            .toList())
                    .orElse(List.of());
        }

        var selectedSchemas = knowledgeBaseIds.stream()
                .map(id -> schemas.findActive(organizationId, id)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Metadata filters require an active Schema for knowledge base " + id)))
                .toList();
        var compatible = new LinkedHashMap<String, MetadataField>();
        selectedSchemas.getFirst().schema().fields().stream()
                .filter(MetadataField::filterable)
                .forEach(field -> compatible.put(field.key(), field));
        for (var schema : selectedSchemas.subList(1, selectedSchemas.size())) {
            var fields = schema.schema().fields().stream()
                    .collect(java.util.stream.Collectors.toMap(MetadataField::key, field -> field));
            compatible.entrySet().removeIf(entry -> {
                var other = fields.get(entry.getKey());
                return other == null || !other.filterable() || other.type() != entry.getValue().type();
            });
        }
        return List.copyOf(compatible.values());
    }
}
