package com.yanyue.rag.domain.port;

public interface CredentialCipher {
    String encrypt(String plaintext);

    String decrypt(String envelope);
}
