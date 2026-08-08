package com.yanyue.rag.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmCredentialCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OLD_KEY = encodedKey((byte) 7);

    @Test
    void encryptsWithUniqueNonceAndDecrypts() {
        var cipher = new AesGcmCredentialCipher(KEY, new SecureRandom());

        var first = cipher.encrypt("secret-value");
        var second = cipher.encrypt("secret-value");

        assertNotEquals(first, second);
        assertTrue(first.startsWith("v2:primary:"));
        assertEquals("primary", cipher.envelopeKeyId(first));
        assertTrue(cipher.usesActiveKey(first));
        assertEquals("secret-value", cipher.decrypt(first));
        assertEquals("secret-value", cipher.decrypt(second));
    }

    @Test
    void decryptsLegacyEnvelopeWithFallbackAndReencryptsUsingActiveKey() {
        var legacyEnvelope = legacyEncrypt("legacy-secret", OLD_KEY);
        var cipher = new AesGcmCredentialCipher(
                "current", KEY, "previous=" + OLD_KEY, new SecureRandom());

        assertEquals("legacy-v1", cipher.envelopeKeyId(legacyEnvelope));
        assertFalse(cipher.usesActiveKey(legacyEnvelope));
        assertTrue(cipher.canDecrypt(legacyEnvelope));
        assertEquals("legacy-secret", cipher.decrypt(legacyEnvelope));

        var rotated = cipher.reencrypt(legacyEnvelope);
        assertTrue(rotated.startsWith("v2:current:"));
        assertEquals("legacy-secret", cipher.decrypt(rotated));
    }

    @Test
    void decryptsVersionTwoEnvelopeWithConfiguredPreviousKey() {
        var previous = new AesGcmCredentialCipher("previous", OLD_KEY, "", new SecureRandom());
        var envelope = previous.encrypt("previous-secret");
        var current = new AesGcmCredentialCipher(
                "current", KEY, "previous=" + OLD_KEY, new SecureRandom());

        assertEquals("previous", current.envelopeKeyId(envelope));
        assertTrue(current.canDecrypt(envelope));
        assertEquals("previous-secret", current.decrypt(envelope));
        assertFalse(current.usesActiveKey(envelope));
    }

    @Test
    void rejectsUnknownKeyIdAndDuplicateConfiguration() {
        var previous = new AesGcmCredentialCipher("missing", OLD_KEY, "", new SecureRandom());
        var envelope = previous.encrypt("unavailable-secret");
        var current = new AesGcmCredentialCipher("current", KEY, "", new SecureRandom());

        assertFalse(current.canDecrypt(envelope));
        assertThrows(IllegalStateException.class, () -> current.decrypt(envelope));
        assertThrows(IllegalStateException.class, () -> new AesGcmCredentialCipher(
                "current", KEY, "current=" + OLD_KEY, new SecureRandom()));
    }

    @Test
    void rejectsInvalidMasterKeyLength() {
        var shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new AesGcmCredentialCipher(shortKey, new SecureRandom()));
    }

    @Test
    void rejectsModifiedCiphertext() {
        var cipher = new AesGcmCredentialCipher(KEY, new SecureRandom());
        var envelope = cipher.encrypt("secret-value");
        var parts = envelope.split(":", 4);
        var ciphertext = Base64.getUrlDecoder().decode(parts[3]);
        ciphertext[0] ^= 0x01;
        var modified = parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(modified));
    }

    @Test
    void authenticatesTheKeyIdentifier() {
        var cipher = new AesGcmCredentialCipher(
                "primary", KEY, "secondary=" + KEY, new SecureRandom());
        var envelope = cipher.encrypt("secret-value");
        var modified = envelope.replaceFirst("v2:primary:", "v2:secondary:");

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(modified));
    }

    private static String encodedKey(byte value) {
        var bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String legacyEncrypt(String plaintext, String encodedKey) {
        try {
            var nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(encodedKey), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD("rag:model-profile:v1".getBytes(StandardCharsets.UTF_8));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
