package com.yanyue.rag.domain.port;

public interface CredentialKeyring extends CredentialCipher {
    String activeKeyId();

    String envelopeKeyId(String envelope);

    boolean usesActiveKey(String envelope);

    boolean canDecrypt(String envelope);

    String reencrypt(String envelope);
}
