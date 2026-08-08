package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.knowledge.UploadRegistration;
import java.util.Optional;
import java.util.UUID;

public interface IngestionRegistrationPort {
    void register(UploadRegistration registration);
    Optional<UploadRegistration> find(UUID organizationId, UUID userId, UUID uploadId);
    UUID completeAndEnqueue(UploadRegistration registration, StoredObjectInfo storedObject);
}
