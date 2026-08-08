package com.yanyue.rag.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.contract.chat.FilterOperator;
import com.yanyue.rag.contract.chat.MetadataFilter;
import com.yanyue.rag.contract.knowledge.MetadataFieldRequest;
import com.yanyue.rag.contract.knowledge.MetadataFieldType;
import com.yanyue.rag.contract.knowledge.UpdateMetadataSchemaRequest;
import com.yanyue.rag.domain.knowledge.MetadataSchema;
import com.yanyue.rag.domain.port.MetadataSchemaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetadataSchemaServiceTest {
    @Test
    void validatesRequiredTypesAllowedValuesAndUnknownFields() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var service = service(organizationId, knowledgeBaseId);
        service.activate(organizationId, knowledgeBaseId, new UpdateMetadataSchemaRequest(List.of(
                new MetadataFieldRequest("region", "区域", MetadataFieldType.TEXT, true, true,
                        List.of("cn", "sg")),
                new MetadataFieldRequest("risk_score", "风险分", MetadataFieldType.NUMBER, false, true, List.of())
        )));

        service.validateMetadata(organizationId, knowledgeBaseId, Map.of("region", "cn", "risk_score", 7));

        assertThrows(IllegalArgumentException.class,
                () -> service.validateMetadata(organizationId, knowledgeBaseId, Map.of("risk_score", 7)));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateMetadata(organizationId, knowledgeBaseId, Map.of("region", "eu")));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateMetadata(organizationId, knowledgeBaseId,
                        Map.of("region", "cn", "unknown", true)));
    }

    @Test
    void enrichesCustomFiltersWithSchemaTypes() {
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        var service = service(organizationId, knowledgeBaseId);
        service.activate(organizationId, knowledgeBaseId, new UpdateMetadataSchemaRequest(List.of(
                new MetadataFieldRequest("risk_score", "风险分", MetadataFieldType.NUMBER, false, true, List.of())
        )));

        var result = service.validateFilters(organizationId, List.of(knowledgeBaseId),
                List.of(new MetadataFilter("risk_score", FilterOperator.GTE, 5)));

        assertEquals(MetadataFieldType.NUMBER, result.getFirst().valueType());
        assertThrows(IllegalArgumentException.class, () -> service.validateFilters(
                organizationId, List.of(knowledgeBaseId),
                List.of(new MetadataFilter("risk_score", FilterOperator.CONTAINS, 5))));
    }

    private MetadataSchemaService service(UUID organizationId, UUID knowledgeBaseId) {
        return new MetadataSchemaService(new InMemoryRepository(organizationId, knowledgeBaseId));
    }

    private static final class InMemoryRepository implements MetadataSchemaRepository {
        private final UUID organizationId;
        private final UUID knowledgeBaseId;
        private final List<StoredMetadataSchema> values = new ArrayList<>();
        private final List<StoredMetadataSchema> organizationValues = new ArrayList<>();

        private InMemoryRepository(UUID organizationId, UUID knowledgeBaseId) {
            this.organizationId = organizationId;
            this.knowledgeBaseId = knowledgeBaseId;
        }

        @Override
        public Optional<StoredMetadataSchema> findActive(UUID organizationId, UUID knowledgeBaseId) {
            if (!this.organizationId.equals(organizationId) || !this.knowledgeBaseId.equals(knowledgeBaseId)) {
                return Optional.empty();
            }
            return values.stream().filter(StoredMetadataSchema::active).findFirst();
        }

        @Override
        public List<StoredMetadataSchema> findAll(UUID organizationId, UUID knowledgeBaseId) {
            return this.organizationId.equals(organizationId) && this.knowledgeBaseId.equals(knowledgeBaseId)
                    ? List.copyOf(values) : List.of();
        }

        @Override
        public StoredMetadataSchema activate(UUID organizationId, UUID knowledgeBaseId, MetadataSchema schema) {
            var inactive = values.stream().map(value -> new StoredMetadataSchema(
                    value.id(), value.schema(), false, value.createdAt())).toList();
            values.clear();
            values.addAll(inactive);
            var stored = new StoredMetadataSchema(UUID.randomUUID(), schema, true, Instant.EPOCH);
            values.add(stored);
            return stored;
        }

        @Override
        public boolean deactivate(UUID organizationId, UUID knowledgeBaseId) {
            var hadActive = values.stream().anyMatch(StoredMetadataSchema::active);
            var inactive = values.stream().map(value -> new StoredMetadataSchema(
                    value.id(), value.schema(), false, value.createdAt())).toList();
            values.clear();
            values.addAll(inactive);
            return hadActive;
        }

        @Override
        public Optional<StoredMetadataSchema> findActiveForOrganization(UUID organizationId) {
            if (!this.organizationId.equals(organizationId)) return Optional.empty();
            return organizationValues.stream().filter(StoredMetadataSchema::active).findFirst();
        }

        @Override
        public List<StoredMetadataSchema> findAllForOrganization(UUID organizationId) {
            return this.organizationId.equals(organizationId) ? List.copyOf(organizationValues) : List.of();
        }

        @Override
        public StoredMetadataSchema activateForOrganization(
                UUID organizationId,
                List<com.yanyue.rag.domain.knowledge.MetadataField> fields
        ) {
            var inactive = organizationValues.stream().map(value -> new StoredMetadataSchema(
                    value.id(), value.schema(), false, value.createdAt())).toList();
            organizationValues.clear();
            organizationValues.addAll(inactive);
            var stored = new StoredMetadataSchema(
                    UUID.randomUUID(),
                    new MetadataSchema(null, organizationValues.size() + 1, fields),
                    true,
                    Instant.EPOCH);
            organizationValues.add(stored);
            return stored;
        }

        @Override
        public void inheritOrganizationSchema(UUID organizationId, UUID knowledgeBaseId) {
            findActiveForOrganization(organizationId).ifPresent(stored ->
                    activate(organizationId, knowledgeBaseId,
                            new MetadataSchema(knowledgeBaseId, stored.schema().version(), stored.schema().fields())));
        }
    }
}
