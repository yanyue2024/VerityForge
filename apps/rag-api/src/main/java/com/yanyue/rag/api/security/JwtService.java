package com.yanyue.rag.api.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] key;
    private final long ttlSeconds;

    public JwtService(ObjectMapper objectMapper, Clock clock,
                      @Value("${rag.auth.jwt-secret}") String secret,
                      @Value("${rag.auth.token-ttl-seconds}") long ttlSeconds) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.key = sha256(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(AuthenticatedUser user) {
        var now = clock.instant();
        var header = encodeJson(Map.of("alg", "HS256", "typ", "JWT"));
        var payload = encodeJson(Map.of(
                "sub", user.userId().toString(),
                "org", user.organizationId().toString(),
                "username", user.username(),
                "role", user.role(),
                "ver", user.authVersion(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(ttlSeconds).getEpochSecond()
        ));
        var content = header + "." + payload;
        return content + "." + ENCODER.encodeToString(hmac(content));
    }

    public Optional<AuthenticatedUser> verify(String token) {
        try {
            var parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            var content = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(hmac(content), DECODER.decode(parts[2]))) return Optional.empty();
            var payload = objectMapper.readTree(DECODER.decode(parts[1]));
            if (payload.path("exp").asLong(0) <= clock.instant().getEpochSecond()) return Optional.empty();
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(payload.path("sub").asText()),
                    UUID.fromString(payload.path("org").asText()),
                    payload.path("username").asText(),
                    payload.path("role").asText(),
                    payload.path("ver").asLong(-1)
            ));
        } catch (RuntimeException | IOException exception) {
            return Optional.empty();
        }
    }

    public Instant expiresAt() {
        return clock.instant().plusSeconds(ttlSeconds);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode JWT", exception);
        }
    }

    private byte[] hmac(String content) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
