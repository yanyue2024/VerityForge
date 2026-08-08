package com.yanyue.rag.application.knowledge;

import com.yanyue.rag.contract.knowledge.CompleteUploadResponse;
import com.yanyue.rag.contract.knowledge.CreateUploadIntentRequest;
import com.yanyue.rag.contract.knowledge.UploadIntentResponse;
import com.yanyue.rag.domain.knowledge.UploadRegistration;
import com.yanyue.rag.domain.port.IngestionRegistrationPort;
import com.yanyue.rag.domain.port.ObjectStoragePort;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DocumentUploadService {
    private static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/html",
            "text/markdown",
            "text/x-markdown"
    );

    private final ObjectStoragePort storage;
    private final IngestionRegistrationPort registrations;
    private final Clock clock;
    private final MetadataSchemaService metadataSchemas;

    public DocumentUploadService(ObjectStoragePort storage, IngestionRegistrationPort registrations, Clock clock,
                                 MetadataSchemaService metadataSchemas) {
        this.storage = storage;
        this.registrations = registrations;
        this.clock = clock;
        this.metadataSchemas = metadataSchemas;
    }

    public UploadIntentResponse initiate(
            UUID organizationId,
            UUID userId,
            UUID knowledgeBaseId,
            CreateUploadIntentRequest request
    ) {
        if (!SUPPORTED_TYPES.contains(request.contentType())) {
            throw new IllegalArgumentException("Only PDF, DOCX, XLSX, HTML, and Markdown files are supported");
        }
        if (request.validFrom() != null && request.validTo() != null && !request.validTo().isAfter(request.validFrom())) {
            throw new IllegalArgumentException("validTo must be later than validFrom");
        }
        metadataSchemas.validateMetadata(organizationId, knowledgeBaseId, metadata(request));
        var uploadId = UUID.randomUUID();
        var documentId = request.documentId() == null ? UUID.randomUUID() : request.documentId();
        var versionId = UUID.randomUUID();
        var objectKey = organizationId + "/" + knowledgeBaseId + "/" + documentId + "/" + versionId + "/source/"
                + sanitize(request.fileName());
        var registration = new UploadRegistration(uploadId, organizationId, userId, knowledgeBaseId, documentId, versionId,
                request.title().strip(), request.fileName(), request.contentType(), request.byteSize(),
                request.sha256() == null ? null : request.sha256().toLowerCase(), objectKey, metadata(request),
                request.validFrom(), request.validTo(), clock.instant());
        registrations.register(registration);
        var presigned = storage.presignPut(objectKey, request.contentType(), Duration.ofMinutes(15));
        return new UploadIntentResponse(uploadId, documentId, versionId, "PUT", presigned.url(),
                presigned.headers(), presigned.expiresAt());
    }

    public CompleteUploadResponse complete(UUID organizationId, UUID userId, UUID uploadId) {
        var registration = registrations.find(organizationId, userId, uploadId)
                .orElseThrow(() -> new IllegalArgumentException("Upload intent not found"));
        var object = storage.head(registration.objectKey());
        if (object.byteSize() != registration.byteSize()) {
            throw new IllegalArgumentException("Uploaded object size does not match the upload intent");
        }
        var jobId = registrations.completeAndEnqueue(registration, object);
        return new CompleteUploadResponse(jobId, "PENDING");
    }

    private Map<String, Object> metadata(CreateUploadIntentRequest request) {
        if (request.metadata() == null) return Map.of();
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(request.metadata()));
    }

    private String sanitize(String fileName) {
        var normalized = fileName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        return normalized.isBlank() ? "document.bin" : normalized;
    }
}
