package com.yanyue.rag.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanyue.rag.domain.port.KnowledgeBaseRepository;
import com.yanyue.rag.domain.port.ObjectStoragePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeBaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void permanentlyDeletesDatabaseRecordsAndStoredObjects() {
        var repository = mock(KnowledgeBaseRepository.class);
        var storage = mock(ObjectStoragePort.class);
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        when(repository.delete(organizationId, knowledgeBaseId)).thenReturn(Optional.of(
                new KnowledgeBaseRepository.KnowledgeBaseDeletion(
                        List.of("org/kb/document/v1/source/a.pdf", "org/kb/document/v2/source/a.pdf"))));
        var service = service(repository, storage);

        service.delete(organizationId, knowledgeBaseId);

        verify(repository).delete(organizationId, knowledgeBaseId);
        verify(storage).deleteObject("org/kb/document/v1/source/a.pdf");
        verify(storage).deleteObject("org/kb/document/v2/source/a.pdf");
    }

    @Test
    void rejectsDeletionOutsideTheOrganization() {
        var repository = mock(KnowledgeBaseRepository.class);
        var organizationId = UUID.randomUUID();
        var knowledgeBaseId = UUID.randomUUID();
        when(repository.delete(organizationId, knowledgeBaseId)).thenReturn(Optional.empty());
        var service = service(repository, mock(ObjectStoragePort.class));

        var exception = assertThrows(IllegalArgumentException.class,
                () -> service.delete(organizationId, knowledgeBaseId));

        assertEquals("知识库不存在或已被删除", exception.getMessage());
    }

    private KnowledgeBaseService service(KnowledgeBaseRepository repository, ObjectStoragePort storage) {
        return new KnowledgeBaseService(repository, mock(MetadataSchemaService.class), storage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
