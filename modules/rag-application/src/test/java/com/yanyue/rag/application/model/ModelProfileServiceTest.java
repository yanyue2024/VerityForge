package com.yanyue.rag.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yanyue.rag.contract.model.CreateModelProfileRequest;
import com.yanyue.rag.contract.model.ModelProfileTestStatus;
import com.yanyue.rag.contract.model.ModelProfileType;
import com.yanyue.rag.contract.model.ModelProvider;
import com.yanyue.rag.contract.model.UpdateModelProfileRequest;
import com.yanyue.rag.domain.model.ModelProfile;
import com.yanyue.rag.domain.port.CredentialCipher;
import com.yanyue.rag.domain.port.ModelProfileProbePort;
import com.yanyue.rag.domain.port.ModelProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelProfileServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    @Test
    void encryptsApiKeyAndOnlyExposesPresence() {
        var repository = new InMemoryRepository();
        var service = service(repository, target -> new ModelProfileProbePort.ProbeResult(4, "ok", Map.of()));

        var view = service.create(UUID.randomUUID(), new CreateModelProfileRequest(
                ModelProfileType.CHAT,
                ModelProvider.OPENAI_COMPATIBLE,
                "GPT",
                "gpt-5",
                "https://example.test/v1/",
                "plain-secret",
                Map.of("temperature", 0)
        ));

        assertTrue(view.hasApiKey());
        assertEquals("https://example.test/v1", view.baseUrl());
        assertEquals("encrypted:plain-secret", repository.values.get(view.id()).encryptedApiKey());
    }

    @Test
    void probeReceivesDecryptedCredentialAndPersistsCapabilities() {
        var repository = new InMemoryRepository();
        var observedKey = new String[1];
        var service = service(repository, target -> {
            observedKey[0] = target.apiKey();
            return new ModelProfileProbePort.ProbeResult(9, "available", Map.of("protocol", "openai"));
        });
        var organizationId = UUID.randomUUID();
        var created = service.create(organizationId, new CreateModelProfileRequest(
                ModelProfileType.QUERY_REWRITE,
                ModelProvider.OPENAI_COMPATIBLE,
                "Rewrite",
                "gpt-5",
                "https://example.test/v1",
                "plain-secret",
                Map.of()
        ));

        var tested = service.test(organizationId, created.id());

        assertEquals("plain-secret", observedKey[0]);
        assertEquals(ModelProfileTestStatus.PASSED, tested.status());
        assertEquals("openai", tested.capabilities().get("protocol"));
    }

    @Test
    void rejectsLocalBgeForChatProfiles() {
        var service = service(new InMemoryRepository(),
                target -> new ModelProfileProbePort.ProbeResult(0, "ok", Map.of()));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                UUID.randomUUID(),
                new CreateModelProfileRequest(ModelProfileType.CHAT, ModelProvider.LOCAL_BGE,
                        "invalid", "bge", "http://localhost:18091", null, Map.of())
        ));
    }

    @Test
    void deletingAConnectionDisablesItAndErasesTheCredential() {
        var repository = new InMemoryRepository();
        var service = service(repository, target -> new ModelProfileProbePort.ProbeResult(0, "ok", Map.of()));
        var organizationId = UUID.randomUUID();
        var created = service.create(organizationId, new CreateModelProfileRequest(
                ModelProfileType.CHAT,
                ModelProvider.OPENAI_COMPATIBLE,
                "备用模型",
                "gpt-5",
                "https://example.test/v1",
                "plain-secret",
                Map.of()
        ));

        service.delete(organizationId, created.id());

        var deleted = repository.values.get(created.id());
        assertFalse(deleted.enabled());
        assertNull(deleted.encryptedApiKey());
        assertEquals(ModelProfileTestStatus.NOT_TESTED, deleted.testStatus());
    }

    @Test
    void updatesPublishedChatConnectionAndReplacesItsCredential() {
        var repository = new InMemoryRepository();
        var service = service(repository, target -> new ModelProfileProbePort.ProbeResult(0, "ok", Map.of()));
        var organizationId = UUID.randomUUID();
        var created = service.create(organizationId, new CreateModelProfileRequest(
                ModelProfileType.CHAT,
                ModelProvider.OPENAI_COMPATIBLE,
                "线上模型",
                "gpt-5",
                "https://old.example.test/v1",
                "old-secret",
                Map.of("reasoningEffort", "low")
        ));
        repository.activePipelineProfileId = created.id();

        var updated = service.update(organizationId, created.id(), new UpdateModelProfileRequest(
                ModelProvider.OPENAI_COMPATIBLE,
                "线上模型",
                "gpt-5",
                "https://new.example.test/v1",
                "new-secret",
                false,
                Map.of("reasoningEffort", "low"),
                true
        ));

        assertEquals("https://new.example.test/v1", updated.baseUrl());
        assertEquals("encrypted:new-secret", repository.values.get(created.id()).encryptedApiKey());
        assertEquals(ModelProfileTestStatus.NOT_TESTED, updated.testStatus());
    }

    @Test
    void keepsStoredCredentialWhenPublishedChatUpdateLeavesApiKeyBlank() {
        var repository = new InMemoryRepository();
        var service = service(repository, target -> new ModelProfileProbePort.ProbeResult(0, "ok", Map.of()));
        var organizationId = UUID.randomUUID();
        var created = service.create(organizationId, new CreateModelProfileRequest(
                ModelProfileType.CHAT,
                ModelProvider.OPENAI_COMPATIBLE,
                "线上模型",
                "gpt-5",
                "https://example.test/v1",
                "stored-secret",
                Map.of()
        ));
        repository.activePipelineProfileId = created.id();

        service.update(organizationId, created.id(), new UpdateModelProfileRequest(
                ModelProvider.OPENAI_COMPATIBLE,
                "线上模型新名称",
                "gpt-5",
                "https://example.test/v1",
                null,
                false,
                Map.of(),
                true
        ));

        assertEquals("encrypted:stored-secret", repository.values.get(created.id()).encryptedApiKey());
    }

    @Test
    void rejectsDeletingThePublishedConnection() {
        var repository = new InMemoryRepository();
        var service = service(repository, target -> new ModelProfileProbePort.ProbeResult(0, "ok", Map.of()));
        var organizationId = UUID.randomUUID();
        var created = service.create(organizationId, new CreateModelProfileRequest(
                ModelProfileType.CHAT,
                ModelProvider.OPENAI_COMPATIBLE,
                "线上模型",
                "gpt-5",
                "https://example.test/v1",
                "plain-secret",
                Map.of()
        ));
        repository.activePipelineProfileId = created.id();

        assertThrows(IllegalArgumentException.class, () -> service.delete(organizationId, created.id()));
        assertTrue(repository.values.get(created.id()).enabled());
    }

    private ModelProfileService service(InMemoryRepository repository, ModelProfileProbePort probe) {
        CredentialCipher cipher = new CredentialCipher() {
            @Override
            public String encrypt(String plaintext) {
                return "encrypted:" + plaintext;
            }

            @Override
            public String decrypt(String envelope) {
                return envelope.substring("encrypted:".length());
            }
        };
        return new ModelProfileService(repository, cipher, probe, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class InMemoryRepository implements ModelProfileRepository {
        private final Map<UUID, ModelProfile> values = new LinkedHashMap<>();
        private UUID activePipelineProfileId;

        @Override
        public ModelProfile save(ModelProfile profile) {
            values.put(profile.id(), profile);
            return profile;
        }

        @Override
        public Optional<ModelProfile> findById(UUID organizationId, UUID id) {
            return Optional.ofNullable(values.get(id)).filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public Optional<ModelProfile> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public boolean isUsedByActiveGeneration(UUID id) {
            return false;
        }

        @Override
        public boolean isUsedByActivePipeline(UUID id) {
            return id.equals(activePipelineProfileId);
        }

        @Override
        public List<ModelProfile> findAll(UUID organizationId) {
            return values.values().stream()
                    .filter(value -> value.organizationId().equals(organizationId))
                    .toList();
        }
    }
}
