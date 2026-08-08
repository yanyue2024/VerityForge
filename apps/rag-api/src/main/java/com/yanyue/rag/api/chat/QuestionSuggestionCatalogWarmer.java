package com.yanyue.rag.api.chat;

import com.yanyue.rag.application.chat.suggestion.QuestionSuggestionService;
import com.yanyue.rag.contract.chat.RunMode;
import com.yanyue.rag.domain.port.QuestionSuggestionWarmTargetPort;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QuestionSuggestionCatalogWarmer {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuestionSuggestionCatalogWarmer.class);

    private final QuestionSuggestionService suggestions;
    private final QuestionSuggestionWarmTargetPort targets;
    private final Set<WarmKey> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("question-suggestion-warmer-", 0).factory());

    public QuestionSuggestionCatalogWarmer(
            QuestionSuggestionService suggestions,
            QuestionSuggestionWarmTargetPort targets
    ) {
        this.suggestions = suggestions;
        this.targets = targets;
    }

    @Scheduled(initialDelayString = "${rag.question-suggestions.initial-delay-ms:1500}",
            fixedDelayString = "${rag.question-suggestions.refresh-delay-ms:300000}")
    public void refreshAll() {
        targets.findEnabledTargets().forEach(target -> {
            enqueue(target.organizationId(), target.userId(), RunMode.DEEP, null);
            enqueue(target.organizationId(), target.userId(), RunMode.FAST, null);
            target.knowledgeBaseIds().forEach(knowledgeBaseId -> {
                enqueue(target.organizationId(), target.userId(), RunMode.DEEP, knowledgeBaseId);
                enqueue(target.organizationId(), target.userId(), RunMode.FAST, knowledgeBaseId);
            });
        });
    }

    public void ensureAvailable(
            UUID organizationId,
            UUID userId,
            RunMode requestedMode,
            List<UUID> knowledgeBaseIds
    ) {
        var mode = requestedMode == RunMode.FAST ? RunMode.FAST : RunMode.DEEP;
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            enqueue(organizationId, userId, mode, null);
            return;
        }
        knowledgeBaseIds.forEach(knowledgeBaseId -> enqueue(organizationId, userId, mode, knowledgeBaseId));
        enqueue(organizationId, userId, mode, null);
    }

    private void enqueue(UUID organizationId, UUID userId, RunMode mode, UUID knowledgeBaseId) {
        var key = new WarmKey(organizationId, userId, mode, knowledgeBaseId);
        if (!queued.add(key)) return;
        worker.submit(() -> {
            var complete = false;
            var successful = false;
            try {
                complete = suggestions.warmCatalog(organizationId, userId, mode, knowledgeBaseId);
                successful = true;
            } catch (RuntimeException exception) {
                LOGGER.warn("Question suggestion catalog warm failed for mode {} and scope {}: {}",
                        mode, knowledgeBaseId == null ? "all" : "knowledge-base",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            } finally {
                queued.remove(key);
                if (successful && !complete) enqueue(organizationId, userId, mode, knowledgeBaseId);
            }
        });
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }

    private record WarmKey(UUID organizationId, UUID userId, RunMode mode, UUID knowledgeBaseId) {
    }
}
