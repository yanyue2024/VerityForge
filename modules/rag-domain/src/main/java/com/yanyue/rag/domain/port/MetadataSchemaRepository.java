package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.knowledge.MetadataSchema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataSchemaRepository {
    Optional<StoredMetadataSchema> findActive(UUID organizationId, UUID knowledgeBaseId);

    List<StoredMetadataSchema> findAll(UUID organizationId, UUID knowledgeBaseId);

    StoredMetadataSchema activate(UUID organizationId, UUID knowledgeBaseId, MetadataSchema schema);

    boolean deactivate(UUID organizationId, UUID knowledgeBaseId);

    Optional<StoredMetadataSchema> findActiveForOrganization(UUID organizationId);

    List<StoredMetadataSchema> findAllForOrganization(UUID organizationId);

    StoredMetadataSchema activateForOrganization(UUID organizationId, List<com.yanyue.rag.domain.knowledge.MetadataField> fields);

    void inheritOrganizationSchema(UUID organizationId, UUID knowledgeBaseId);

    record StoredMetadataSchema(UUID id, MetadataSchema schema, boolean active, Instant createdAt) {
    }
}
