package com.yanyue.rag.application.knowledge;

import com.yanyue.rag.contract.knowledge.IndexGenerationView;
import com.yanyue.rag.contract.knowledge.IndexRebuildJobView;
import com.yanyue.rag.contract.knowledge.StartIndexRebuildRequest;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.IndexGenerationRepository;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexGenerationService {
    private final IndexGenerationRepository generations;
    private final ModelProfileRepository profiles;

    public IndexGenerationService(IndexGenerationRepository generations, ModelProfileRepository profiles) {
        this.generations = generations;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public List<IndexGenerationView> list(UUID organizationId, UUID knowledgeBaseId) {
        requireKnowledgeBase(organizationId, knowledgeBaseId);
        return generations.findAll(organizationId, knowledgeBaseId).stream().map(this::toView).toList();
    }

    @Transactional
    public IndexGenerationView rebuild(
            UUID organizationId,
            UUID knowledgeBaseId,
            StartIndexRebuildRequest request
    ) {
        requireKnowledgeBase(organizationId, knowledgeBaseId);
        if (generations.hasActiveRebuild(knowledgeBaseId)) {
            throw new IllegalArgumentException("An index rebuild is already queued or running for this knowledge base");
        }
        var profile = profiles.findById(organizationId, request.embeddingProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Embedding model profile not found"));
        validateEmbeddingProfile(profile);
        var dimension = integerCapability(profile, "dimension");
        var modelVersion = stringSetting(profile, "modelVersion", profile.modelName());
        var generation = generations.createBuildingGeneration(
                knowledgeBaseId,
                profile.id(),
                profile.modelName(),
                modelVersion,
                dimension,
                generations.chunkPolicyVersion(knowledgeBaseId)
        );
        generations.createRebuildJob(organizationId, knowledgeBaseId, generation.id());
        return toView(generation);
    }

    @Transactional
    public IndexGenerationView activate(UUID organizationId, UUID knowledgeBaseId, UUID generationId) {
        requireKnowledgeBase(organizationId, knowledgeBaseId);
        if (generations.hasActiveRebuild(knowledgeBaseId)) {
            throw new IllegalArgumentException("Cannot activate a Generation while a rebuild is running");
        }
        return toView(generations.activate(organizationId, knowledgeBaseId, generationId));
    }

    private void validateEmbeddingProfile(ModelProfile profile) {
        if (profile.profileType() != ModelProfileType.EMBEDDING || !profile.enabled()) {
            throw new IllegalArgumentException("An enabled EMBEDDING profile is required");
        }
        if (profile.testStatus() != ModelProfileTestStatus.PASSED) {
            throw new IllegalArgumentException("Embedding profile must pass its endpoint test before rebuilding");
        }
    }

    private int integerCapability(ModelProfile profile, String key) {
        var value = profile.capabilities().get(key);
        if (!(value instanceof Number number) || number.intValue() <= 0) {
            throw new IllegalArgumentException("Embedding profile has no verified dimension capability");
        }
        return number.intValue();
    }

    private String stringSetting(ModelProfile profile, String key, String fallback) {
        var value = profile.settings().get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : fallback;
    }

    private void requireKnowledgeBase(UUID organizationId, UUID knowledgeBaseId) {
        if (!generations.knowledgeBaseExists(organizationId, knowledgeBaseId)) {
            throw new IllegalArgumentException("Knowledge base not found");
        }
    }

    private IndexGenerationView toView(IndexGenerationRepository.GenerationRecord generation) {
        var job = generations.findJobByGeneration(generation.id()).map(this::toView).orElse(null);
        return new IndexGenerationView(generation.id(), generation.generationNumber(), generation.status(),
                generation.embeddingProfileId(), generation.modelId(), generation.modelVersion(),
                generation.dimension(), generation.chunkPolicyVersion(), generation.vectorCount(), job,
                generation.createdAt(), generation.activatedAt(), generation.retiredAt());
    }

    private IndexRebuildJobView toView(IndexGenerationRepository.RebuildJobRecord job) {
        return new IndexRebuildJobView(job.id(), job.generationId(), job.status(), job.totalChunks(),
                job.completedChunks(), job.reusedChunks(), job.failedChunks(), job.attempt(), job.maxAttempts(),
                job.nextAttemptAt(), job.errorMessage(),
                job.startedAt(), job.completedAt(), job.createdAt());
    }
}
