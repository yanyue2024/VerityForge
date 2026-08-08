package com.yanyue.rag.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanyue.rag.domain.knowledge.MetadataField;
import com.yanyue.rag.domain.knowledge.MetadataSchema;
import com.yanyue.rag.domain.knowledge.MetadataValueType;
import com.yanyue.rag.domain.port.MetadataFilterValueRepository;
import com.yanyue.rag.domain.port.MetadataSchemaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetadataFilterOptionsServiceTest {
    @Test
    void returnsOnlyCompatibleFilterableFieldsAndDatabaseValues() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var firstKnowledgeBase = UUID.randomUUID();
        var secondKnowledgeBase = UUID.randomUUID();
        var schemas = mock(MetadataSchemaRepository.class);
        var values = mock(MetadataFilterValueRepository.class);
        var category = field("category", "所属种类", MetadataValueType.TEXT_LIST, true);
        var fileType = field("file_type", "文档类型", MetadataValueType.TEXT, true);
        var privateField = field("internal_note", "内部备注", MetadataValueType.TEXT, false);
        when(schemas.findActive(organizationId, firstKnowledgeBase)).thenReturn(Optional.of(stored(
                firstKnowledgeBase, List.of(category, fileType, privateField))));
        when(schemas.findActive(organizationId, secondKnowledgeBase)).thenReturn(Optional.of(stored(
                secondKnowledgeBase, List.of(category, fileType))));
        when(values.values(organizationId, userId, List.of(firstKnowledgeBase, secondKnowledgeBase), category))
                .thenReturn(new MetadataFilterValueRepository.FieldValues(true, List.of("云原生", "数据平台")));
        when(values.values(organizationId, userId, List.of(firstKnowledgeBase, secondKnowledgeBase), fileType))
                .thenReturn(new MetadataFilterValueRepository.FieldValues(true, List.of("DOCX", "PDF")));

        var result = new MetadataFilterOptionsService(schemas, values).options(
                organizationId, userId, List.of(firstKnowledgeBase, secondKnowledgeBase));

        assertEquals(List.of("category", "file_type"),
                result.fields().stream().map(field -> field.key()).toList());
        assertEquals(List.of("云原生", "数据平台"), result.fields().getFirst().values());
    }

    private MetadataField field(String key, String label, MetadataValueType type, boolean filterable) {
        return new MetadataField(key, label, type, false, filterable, List.of());
    }

    private MetadataSchemaRepository.StoredMetadataSchema stored(UUID knowledgeBaseId, List<MetadataField> fields) {
        return new MetadataSchemaRepository.StoredMetadataSchema(
                UUID.randomUUID(), new MetadataSchema(knowledgeBaseId, 1, fields), true, Instant.EPOCH);
    }
}
