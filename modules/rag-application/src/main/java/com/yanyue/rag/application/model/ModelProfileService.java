package com.yanyue.rag.application.model;

import com.yanyue.rag.contract.model.CreateModelProfileRequest;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileTestView;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProfileView;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.contract.model.UpdateModelProfileRequest;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.ModelProfileProbePort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelProfileService {
    private final ModelProfileRepository repository;
    private final CredentialCipher credentialCipher;
    private final ModelProfileProbePort probePort;
    private final Clock clock;

    public ModelProfileService(
            ModelProfileRepository repository,
            CredentialCipher credentialCipher,
            ModelProfileProbePort probePort,
            Clock clock
    ) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.probePort = probePort;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ModelProfileView> list(UUID organizationId) {
        return repository.findAll(organizationId).stream().map(this::toView).toList();
    }

    @Transactional
    public ModelProfileView create(UUID organizationId, CreateModelProfileRequest request) {
        validate(request.profileType(), request.provider(), request.baseUrl(), request.apiKey(), false);
        var now = clock.instant();
        var profile = new ModelProfile(
                UUID.randomUUID(),
                organizationId,
                request.profileType(),
                request.provider(),
                request.name().strip(),
                request.modelName().strip(),
                normalizeBaseUrl(request.baseUrl()),
                encryptOptional(request.apiKey()),
                request.settings(),
                true,
                ModelProfileTestStatus.NOT_TESTED,
                null,
                null,
                Map.of(),
                now,
                now
        );
        return toView(repository.save(profile));
    }

    @Transactional
    public ModelProfileView update(UUID organizationId, UUID profileId, UpdateModelProfileRequest request) {
        var existing = requireProfile(organizationId, profileId);
        requireUpdateAllowed(existing);
        var encryptedApiKey = existing.encryptedApiKey();
        if (request.clearApiKey()) {
            encryptedApiKey = null;
        } else if (hasText(request.apiKey())) {
            encryptedApiKey = credentialCipher.encrypt(request.apiKey().strip());
        }
        validate(existing.profileType(), request.provider(), request.baseUrl(), encryptedApiKey, true);
        var updated = new ModelProfile(
                existing.id(),
                existing.organizationId(),
                existing.profileType(),
                request.provider(),
                request.name().strip(),
                request.modelName().strip(),
                normalizeBaseUrl(request.baseUrl()),
                encryptedApiKey,
                request.settings(),
                request.enabled(),
                ModelProfileTestStatus.NOT_TESTED,
                null,
                null,
                Map.of(),
                existing.createdAt(),
                clock.instant()
        );
        return toView(repository.save(updated));
    }

    @Transactional
    public ModelProfileView disable(UUID organizationId, UUID profileId) {
        var existing = requireProfile(organizationId, profileId);
        requireNotBoundToRuntime(existing);
        if (!existing.enabled()) return toView(existing);
        return toView(repository.save(copyWithState(existing, false, existing.testStatus(),
                existing.lastTestedAt(), existing.lastTestMessage(), existing.capabilities())));
    }

    @Transactional
    public void delete(UUID organizationId, UUID profileId) {
        var existing = requireProfile(organizationId, profileId);
        requireNotBoundToRuntime(existing);
        repository.save(new ModelProfile(
                existing.id(),
                existing.organizationId(),
                existing.profileType(),
                existing.provider(),
                existing.name(),
                existing.modelName(),
                existing.baseUrl(),
                null,
                existing.settings(),
                false,
                ModelProfileTestStatus.NOT_TESTED,
                null,
                null,
                Map.of(),
                existing.createdAt(),
                clock.instant()
        ));
    }

    @Transactional
    public ModelProfileTestView test(UUID organizationId, UUID profileId) {
        var profile = requireProfile(organizationId, profileId);
        if (!profile.enabled()) throw new IllegalArgumentException("Disabled model profiles cannot be tested");
        var testedAt = clock.instant();
        try {
            var result = probePort.probe(new ModelProfileProbePort.ProbeTarget(
                    profile.profileType(),
                    profile.provider(),
                    profile.modelName(),
                    profile.baseUrl(),
                    decryptOptional(profile.encryptedApiKey()),
                    profile.settings()
            ));
            var saved = repository.save(copyWithState(profile, true, ModelProfileTestStatus.PASSED,
                    testedAt, result.message(), result.capabilities()));
            return new ModelProfileTestView(saved.id(), saved.testStatus(), result.latencyMs(),
                    saved.lastTestMessage(), saved.capabilities(), testedAt);
        } catch (RuntimeException exception) {
            var message = safeFailureMessage(exception);
            var saved = repository.save(copyWithState(profile, true, ModelProfileTestStatus.FAILED,
                    testedAt, message, Map.of()));
            return new ModelProfileTestView(saved.id(), saved.testStatus(), 0, message, Map.of(), testedAt);
        }
    }

    private ModelProfile requireProfile(UUID organizationId, UUID profileId) {
        return repository.findById(organizationId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("Model profile not found"));
    }

    private void requireNotBoundToRuntime(ModelProfile profile) {
        if (profile.profileType() == ModelProfileType.EMBEDDING
                && repository.isUsedByActiveGeneration(profile.id())) {
            throw new IllegalArgumentException(
                    "Embedding profiles bound to retained Index Generations cannot be changed or disabled");
        }
        if (repository.isUsedByActivePipeline(profile.id())) {
            throw new IllegalArgumentException(
                    "该模型正在被线上配置使用。请先选择另一个模型并发布配置，再删除此连接");
        }
    }

    private void requireUpdateAllowed(ModelProfile profile) {
        if (profile.profileType() == ModelProfileType.EMBEDDING
                && repository.isUsedByActiveGeneration(profile.id())) {
            throw new IllegalArgumentException(
                    "Embedding profiles bound to retained Index Generations cannot be changed");
        }
    }

    private ModelProfile copyWithState(
            ModelProfile profile,
            boolean enabled,
            ModelProfileTestStatus testStatus,
            java.time.Instant lastTestedAt,
            String lastTestMessage,
            Map<String, Object> capabilities
    ) {
        return new ModelProfile(profile.id(), profile.organizationId(), profile.profileType(), profile.provider(),
                profile.name(), profile.modelName(), profile.baseUrl(), profile.encryptedApiKey(), profile.settings(),
                enabled, testStatus, lastTestedAt, lastTestMessage, capabilities, profile.createdAt(), clock.instant());
    }

    private void validate(
            ModelProfileType profileType,
            ModelProvider provider,
            String baseUrl,
            String credential,
            boolean credentialIsEncrypted
    ) {
        if (provider == ModelProvider.LOCAL_BGE
                && profileType != ModelProfileType.EMBEDDING
                && profileType != ModelProfileType.RERANK) {
            throw new IllegalArgumentException("LOCAL_BGE only supports EMBEDDING and RERANK profiles");
        }
        if (provider != ModelProvider.DEMO && !hasText(baseUrl)) {
            throw new IllegalArgumentException("baseUrl is required for non-demo model profiles");
        }
        if (provider == ModelProvider.OPENAI_COMPATIBLE && !hasText(credential)) {
            var label = credentialIsEncrypted ? "stored API key" : "apiKey";
            throw new IllegalArgumentException(label + " is required for OPENAI_COMPATIBLE profiles");
        }
    }

    private String encryptOptional(String apiKey) {
        return hasText(apiKey) ? credentialCipher.encrypt(apiKey.strip()) : null;
    }

    private String decryptOptional(String encryptedApiKey) {
        return hasText(encryptedApiKey) ? credentialCipher.decrypt(encryptedApiKey) : null;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!hasText(baseUrl)) return null;
        var normalized = baseUrl.strip();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl must use http or https");
        }
        return normalized;
    }

    private String safeFailureMessage(RuntimeException exception) {
        var message = exception.getMessage();
        if (!hasText(message)) return "Model profile test failed";
        var normalized = message.replaceAll("(?i)(bearer|api[-_ ]?key)\\s+[^\\s,;]+", "$1 [redacted]");
        return normalized.substring(0, Math.min(500, normalized.length()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ModelProfileView toView(ModelProfile profile) {
        return new ModelProfileView(profile.id(), profile.profileType(), profile.provider(), profile.name(),
                profile.modelName(), profile.baseUrl(), profile.hasApiKey(), profile.settings(), profile.enabled(),
                profile.testStatus(), profile.lastTestedAt(), profile.lastTestMessage(), profile.createdAt(),
                profile.updatedAt());
    }
}
