package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.security.CredentialRotationAudit;
import com.yanyue.rag.domain.security.StoredCredential;
import java.util.List;
import java.util.Optional;

public interface CredentialRotationRepository {
    void lockCredentialStores();

    List<StoredCredential> findAllCredentials();

    void updateCredential(StoredCredential credential, String ciphertext);

    void saveAudit(CredentialRotationAudit audit);

    Optional<CredentialRotationAudit> findLatestAudit();
}
