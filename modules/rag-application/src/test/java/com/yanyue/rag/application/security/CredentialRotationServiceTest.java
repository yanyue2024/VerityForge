package com.yanyue.rag.application.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.domain.port.CredentialKeyring;
import com.yanyue.rag.domain.port.CredentialRotationRepository;
import com.yanyue.rag.domain.security.CredentialLocation;
import com.yanyue.rag.domain.security.CredentialRotationAudit;
import com.yanyue.rag.domain.security.StoredCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialRotationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T14:00:00Z");

    @Test
    void rotatesLegacyAndPreviousCredentialsAndRecordsAudit() {
        var repository = new InMemoryRepository(List.of(
                credential(CredentialLocation.MODEL_PROFILE, "legacy-v1|model-secret"),
                credential(CredentialLocation.EVALUATION_SCHEDULE, "old|schedule-secret"),
                credential(CredentialLocation.EVALUATION_DELIVERY, "current|delivery-secret")));
        var service = service(repository);
        var actor = UUID.randomUUID();

        var before = service.status();
        assertEquals(3, before.totalCredentials());
        assertEquals(2, before.needsRotation());
        assertEquals(0, before.unreadableCredentials());

        var after = service.rotate(actor);
        assertEquals(0, after.needsRotation());
        assertEquals(2, repository.updateCount);
        assertEquals(1, repository.lockCount);
        assertEquals(actor, repository.audit.orElseThrow().rotatedBy());
        assertEquals(2, repository.audit.orElseThrow().rotatedCredentials());
        assertEquals(NOW, repository.audit.orElseThrow().createdAt());

        service.rotate(actor);
        assertEquals(2, repository.updateCount);
        assertEquals(0, repository.audit.orElseThrow().rotatedCredentials());
    }

    @Test
    void doesNotWriteAnythingWhenPreflightFindsUnreadableCredential() {
        var repository = new InMemoryRepository(List.of(
                credential(CredentialLocation.MODEL_PROFILE, "old|model-secret"),
                credential(CredentialLocation.EVALUATION_SCHEDULE, "missing|schedule-secret")));
        var service = service(repository);

        var status = service.status();
        assertEquals(2, status.needsRotation());
        assertEquals(1, status.unreadableCredentials());
        assertThrows(IllegalStateException.class, () -> service.rotate(UUID.randomUUID()));
        assertEquals(0, repository.updateCount);
        assertEquals(Optional.empty(), repository.audit);
    }

    private CredentialRotationService service(InMemoryRepository repository) {
        return new CredentialRotationService(
                new TestKeyring(), repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StoredCredential credential(CredentialLocation location, String ciphertext) {
        return new StoredCredential(location, UUID.randomUUID(), ciphertext);
    }

    private static final class TestKeyring implements CredentialKeyring {
        @Override
        public String activeKeyId() {
            return "current";
        }

        @Override
        public String encrypt(String plaintext) {
            return "current|" + plaintext;
        }

        @Override
        public String decrypt(String envelope) {
            if (!canDecrypt(envelope)) throw new IllegalStateException("missing key");
            return envelope.substring(envelope.indexOf('|') + 1);
        }

        @Override
        public String envelopeKeyId(String envelope) {
            return envelope.substring(0, envelope.indexOf('|'));
        }

        @Override
        public boolean usesActiveKey(String envelope) {
            return envelope.startsWith("current|");
        }

        @Override
        public boolean canDecrypt(String envelope) {
            return !envelope.startsWith("missing|");
        }

        @Override
        public String reencrypt(String envelope) {
            return encrypt(decrypt(envelope));
        }
    }

    private static final class InMemoryRepository implements CredentialRotationRepository {
        private final List<StoredCredential> credentials;
        private Optional<CredentialRotationAudit> audit = Optional.empty();
        private int updateCount;
        private int lockCount;

        private InMemoryRepository(List<StoredCredential> credentials) {
            this.credentials = new ArrayList<>(credentials);
        }

        @Override
        public void lockCredentialStores() {
            lockCount++;
        }

        @Override
        public List<StoredCredential> findAllCredentials() {
            return List.copyOf(credentials);
        }

        @Override
        public void updateCredential(StoredCredential credential, String ciphertext) {
            var index = credentials.indexOf(credential);
            credentials.set(index, new StoredCredential(credential.location(), credential.id(), ciphertext));
            updateCount++;
        }

        @Override
        public void saveAudit(CredentialRotationAudit value) {
            audit = Optional.of(value);
        }

        @Override
        public Optional<CredentialRotationAudit> findLatestAudit() {
            return audit;
        }
    }
}
