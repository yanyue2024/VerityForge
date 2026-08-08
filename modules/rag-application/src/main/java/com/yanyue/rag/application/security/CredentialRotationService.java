package com.yanyue.rag.application.security;

import com.yanyue.rag.contract.security.CredentialRotationAuditView;
import com.yanyue.rag.contract.security.CredentialRotationStatusView;
import com.yanyue.rag.domain.port.CredentialKeyring;
import com.yanyue.rag.domain.port.CredentialRotationRepository;
import com.yanyue.rag.domain.security.CredentialRotationAudit;
import com.yanyue.rag.domain.security.StoredCredential;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CredentialRotationService {
    private final CredentialKeyring keyring;
    private final CredentialRotationRepository repository;
    private final Clock clock;

    public CredentialRotationService(
            CredentialKeyring keyring,
            CredentialRotationRepository repository,
            Clock clock
    ) {
        this.keyring = keyring;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CredentialRotationStatusView status() {
        return status(repository.findAllCredentials());
    }

    @Transactional
    public CredentialRotationStatusView rotate(UUID actorId) {
        repository.lockCredentialStores();
        var credentials = repository.findAllCredentials();

        // Decrypt everything before the first update so a missing or damaged key rolls back the rotation.
        for (var credential : credentials) {
            if (!keyring.canDecrypt(credential.ciphertext())) {
                throw new IllegalStateException("Credential rotation preflight failed; no credentials were changed");
            }
        }

        var sourceCounts = countsBySource(credentials);
        var previousKeyCounts = countsByKey(credentials);
        var rotated = 0;
        for (var credential : credentials) {
            if (keyring.usesActiveKey(credential.ciphertext())) continue;
            repository.updateCredential(credential, keyring.reencrypt(credential.ciphertext()));
            rotated++;
        }

        repository.saveAudit(new CredentialRotationAudit(
                UUID.randomUUID(), keyring.activeKeyId(), actorId, credentials.size(), rotated,
                sourceCounts, previousKeyCounts, clock.instant()));
        return status(repository.findAllCredentials());
    }

    private CredentialRotationStatusView status(java.util.List<StoredCredential> credentials) {
        var needsRotation = 0;
        var unreadable = 0;
        for (var credential : credentials) {
            if (!keyring.usesActiveKey(credential.ciphertext())) needsRotation++;
            if (!keyring.canDecrypt(credential.ciphertext())) unreadable++;
        }
        return new CredentialRotationStatusView(
                keyring.activeKeyId(), credentials.size(), needsRotation, unreadable,
                countsBySource(credentials), countsByKey(credentials),
                repository.findLatestAudit().map(this::view).orElse(null));
    }

    private Map<String, Integer> countsBySource(java.util.List<StoredCredential> credentials) {
        var counts = new LinkedHashMap<String, Integer>();
        credentials.forEach(value -> counts.merge(value.location().name(), 1, Integer::sum));
        return Map.copyOf(counts);
    }

    private Map<String, Integer> countsByKey(java.util.List<StoredCredential> credentials) {
        var counts = new LinkedHashMap<String, Integer>();
        credentials.forEach(value -> counts.merge(keyring.envelopeKeyId(value.ciphertext()), 1, Integer::sum));
        return Map.copyOf(counts);
    }

    private CredentialRotationAuditView view(CredentialRotationAudit audit) {
        return new CredentialRotationAuditView(
                audit.id(), audit.activeKeyId(), audit.rotatedBy(), audit.totalCredentials(),
                audit.rotatedCredentials(), audit.sourceCounts(), audit.previousKeyCounts(), audit.createdAt());
    }
}
