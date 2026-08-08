package com.yanyue.rag.infrastructure.security;

import com.yanyue.rag.domain.port.CredentialKeyring;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmCredentialCipher implements CredentialKeyring {
    private static final String LEGACY_VERSION = "v1";
    private static final String VERSION = "v2";
    private static final byte[] LEGACY_AAD = "rag:model-profile:v1".getBytes(StandardCharsets.UTF_8);
    private static final String AAD_PREFIX = "rag:credential:v2:";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String activeKeyId;
    private final SecretKeySpec activeKey;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom secureRandom;

    @Autowired
    public AesGcmCredentialCipher(
            @Value("${rag.credentials.active-key-id:primary}") String activeKeyId,
            @Value("${rag.credentials.master-key}") String encodedMasterKey,
            @Value("${rag.credentials.decryption-keys:}") String encodedDecryptionKeys
    ) {
        this(activeKeyId, encodedMasterKey, encodedDecryptionKeys, new SecureRandom());
    }

    AesGcmCredentialCipher(String encodedMasterKey, SecureRandom secureRandom) {
        this("primary", encodedMasterKey, "", secureRandom);
    }

    AesGcmCredentialCipher(
            String activeKeyId,
            String encodedMasterKey,
            String encodedDecryptionKeys,
            SecureRandom secureRandom
    ) {
        this.activeKeyId = validateKeyId(activeKeyId, "RAG_CREDENTIAL_ACTIVE_KEY_ID");
        this.activeKey = decodeKey(encodedMasterKey, "RAG_CREDENTIAL_MASTER_KEY");
        var configuredKeys = new LinkedHashMap<String, SecretKeySpec>();
        configuredKeys.put(this.activeKeyId, this.activeKey);
        if (encodedDecryptionKeys != null && !encodedDecryptionKeys.isBlank()) {
            for (var entry : encodedDecryptionKeys.split(",")) {
                var separator = entry.indexOf('=');
                if (separator < 1 || separator == entry.length() - 1) {
                    throw new IllegalStateException(
                            "RAG_CREDENTIAL_DECRYPTION_KEYS must use key-id=base64 entries");
                }
                var keyId = validateKeyId(entry.substring(0, separator), "decryption key id");
                if (configuredKeys.containsKey(keyId)) {
                    throw new IllegalStateException("Duplicate credential key id: " + keyId);
                }
                configuredKeys.put(keyId, decodeKey(
                        entry.substring(separator + 1), "RAG_CREDENTIAL_DECRYPTION_KEYS entry " + keyId));
            }
        }
        this.keys = Map.copyOf(configuredKeys);
        this.secureRandom = secureRandom;
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Credential must not be blank");
        }
        var nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(activeKeyId));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + activeKeyId + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential encryption failed", exception);
        }
    }

    @Override
    public String decrypt(String envelope) {
        if (envelope == null || envelope.isBlank()) return null;
        if (envelope.startsWith(LEGACY_VERSION + ":")) {
            return decryptLegacy(envelope);
        }
        var parts = envelope.split(":", 4);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !KEY_ID.matcher(parts[1]).matches()) {
            throw new IllegalStateException("Credential decryption failed");
        }
        var key = keys.get(parts[1]);
        if (key == null) throw new IllegalStateException("Credential decryption failed");
        try {
            var nonce = Base64.getUrlDecoder().decode(parts[2]);
            var ciphertext = Base64.getUrlDecoder().decode(parts[3]);
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("Invalid credential nonce");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(parts[1]));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Credential decryption failed", exception);
        }
    }

    @Override
    public String envelopeKeyId(String envelope) {
        if (envelope == null || envelope.isBlank()) return "invalid";
        if (envelope.startsWith(LEGACY_VERSION + ":")) {
            return envelope.split(":", 4).length == 3 ? "legacy-v1" : "invalid";
        }
        var parts = envelope.split(":", 5);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !KEY_ID.matcher(parts[1]).matches()) {
            return "invalid";
        }
        return parts[1];
    }

    @Override
    public boolean usesActiveKey(String envelope) {
        return activeKeyId.equals(envelopeKeyId(envelope));
    }

    @Override
    public boolean canDecrypt(String envelope) {
        try {
            decrypt(envelope);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public String reencrypt(String envelope) {
        return encrypt(decrypt(envelope));
    }

    private String decryptLegacy(String envelope) {
        var parts = envelope.split(":", 4);
        if (parts.length != 3) throw new IllegalStateException("Credential decryption failed");
        RuntimeException lastFailure = null;
        for (var key : keys.values()) {
            try {
                var nonce = Base64.getUrlDecoder().decode(parts[1]);
                var ciphertext = Base64.getUrlDecoder().decode(parts[2]);
                if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("Invalid credential nonce");
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
                cipher.updateAAD(LEGACY_AAD);
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                lastFailure = new IllegalStateException("Credential decryption failed", exception);
            }
        }
        throw lastFailure == null ? new IllegalStateException("Credential decryption failed") : lastFailure;
    }

    private byte[] aad(String keyId) {
        return (AAD_PREFIX + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static String validateKeyId(String value, String setting) {
        var keyId = value == null ? "" : value.strip();
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalStateException(setting + " must match " + KEY_ID.pattern());
        }
        return keyId;
    }

    private static SecretKeySpec decodeKey(String encodedKey, String setting) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException(setting + " is required");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(setting + " must be Base64 encoded", exception);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(setting + " must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
