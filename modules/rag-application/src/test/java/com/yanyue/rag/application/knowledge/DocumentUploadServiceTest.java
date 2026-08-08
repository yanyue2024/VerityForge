package com.yanyue.rag.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.contract.knowledge.CreateUploadIntentRequest;
import com.yanyue.rag.domain.knowledge.UploadRegistration;
import com.yanyue.rag.domain.port.IngestionRegistrationPort;
import com.yanyue.rag.domain.port.ObjectStoragePort;
import com.yanyue.rag.domain.port.PresignedUpload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentUploadServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "text/markdown", "text/x-markdown"})
    void acceptsWebDocumentMediaTypes(String mediaType) {
        var storage = mock(ObjectStoragePort.class);
        var registrations = mock(IngestionRegistrationPort.class);
        var metadataSchemas = mock(MetadataSchemaService.class);
        when(storage.presignPut(any(), eq(mediaType), eq(Duration.ofMinutes(15))))
                .thenReturn(new PresignedUpload("https://objects.test/upload", Map.of(), NOW.plusSeconds(900)));
        var service = new DocumentUploadService(storage, registrations,
                Clock.fixed(NOW, ZoneOffset.UTC), metadataSchemas);
        var extension = mediaType.contains("html") ? "html" : "md";

        service.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new CreateUploadIntentRequest("中文手册", "guide." + extension, mediaType,
                        128, null, Map.of(), null, null, null));

        var registration = ArgumentCaptor.forClass(UploadRegistration.class);
        verify(registrations).register(registration.capture());
        assertEquals(mediaType, registration.getValue().contentType());
    }

    @Test
    void rejectsGenericBinaryMediaType() {
        var service = new DocumentUploadService(mock(ObjectStoragePort.class),
                mock(IngestionRegistrationPort.class), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(MetadataSchemaService.class));

        assertThrows(IllegalArgumentException.class, () -> service.initiate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new CreateUploadIntentRequest("未知文件", "guide.md", "application/octet-stream",
                        128, null, Map.of(), null, null, null)));
    }
}
