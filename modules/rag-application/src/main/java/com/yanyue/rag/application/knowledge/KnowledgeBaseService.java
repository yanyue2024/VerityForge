package com.yanyue.rag.application.knowledge;

import com.yanyue.rag.contract.knowledge.CreateKnowledgeBaseRequest;
import com.yanyue.rag.contract.knowledge.KnowledgeBaseView;
import com.yanyue.rag.domain.knowledge.KnowledgeBase;
import com.yanyue.rag.domain.port.KnowledgeBaseRepository;
import com.yanyue.rag.domain.port.ObjectStoragePort;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class KnowledgeBaseService {
    private static final System.Logger log = System.getLogger(KnowledgeBaseService.class.getName());

    private final KnowledgeBaseRepository repository;
    private final MetadataSchemaService schemas;
    private final ObjectStoragePort storage;
    private final Clock clock;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, MetadataSchemaService schemas,
                                ObjectStoragePort storage, Clock clock) {
        this.repository = repository;
        this.schemas = schemas;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public KnowledgeBaseView create(UUID organizationId, CreateKnowledgeBaseRequest request) {
        var knowledgeBase = KnowledgeBase.create(organizationId, request.name(), request.description(), clock.instant());
        var saved = repository.save(knowledgeBase);
        schemas.inheritOrganizationSchema(organizationId, saved.id());
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseView> list(UUID organizationId, UUID userId) {
        var counts = repository.counts(organizationId, userId);
        return repository.findAll(organizationId).stream().map(knowledgeBase -> {
            var count = counts.getOrDefault(knowledgeBase.id(),
                    new KnowledgeBaseRepository.KnowledgeBaseCounts(0, 0, 0, 0, 0,
                            knowledgeBase.updatedAt()));
            return toView(knowledgeBase, count);
        }).sorted(Comparator.comparing(KnowledgeBaseView::updatedAt).reversed()).toList();
    }

    @Transactional
    public void delete(UUID organizationId, UUID knowledgeBaseId) {
        var deleted = repository.delete(organizationId, knowledgeBaseId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在或已被删除"));
        cleanObjectsAfterCommit(knowledgeBaseId, deleted.objectKeys());
    }

    private KnowledgeBaseView toView(KnowledgeBase knowledgeBase) {
        return toView(knowledgeBase, new KnowledgeBaseRepository.KnowledgeBaseCounts(0, 0, 0, 0, 0,
                knowledgeBase.updatedAt()));
    }

    private KnowledgeBaseView toView(KnowledgeBase knowledgeBase, KnowledgeBaseRepository.KnowledgeBaseCounts counts) {
        return new KnowledgeBaseView(knowledgeBase.id(), knowledgeBase.name(), knowledgeBase.description(),
                counts.documentCount(), counts.chunkCount(), counts.readyCount(), counts.processingCount(),
                counts.failedCount(), counts.activityAt());
    }

    private void cleanObjectsAfterCommit(UUID knowledgeBaseId, List<String> objectKeys) {
        if (objectKeys.isEmpty()) return;
        Runnable cleanup = () -> objectKeys.forEach(objectKey -> {
            try {
                storage.deleteObject(objectKey);
            } catch (RuntimeException exception) {
                log.log(System.Logger.Level.WARNING,
                        "Unable to delete retained object " + objectKey
                                + " for knowledge base " + knowledgeBaseId,
                        exception);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }
}
