package com.api.ratelimiter.security;

import com.api.ratelimiter.config.GatewaySecurityProperties;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates API keys against deployment-provided SHA-256 digests.
 *
 * <p>Every configured digest is compared using {@link MessageDigest#isEqual}
 * and the loop never exits early, reducing timing leakage about which key
 * matched. Configuration is immutable after startup, making validation local,
 * lock-free, and suitable for a high-throughput gateway hot path.
 */
@Component
public class ApiKeyValidator {

    private static final int SHA_256_HEX_LENGTH = 64;

    private final List<byte[]> acceptedDigests;
    private final int maxLength;

    public ApiKeyValidator(GatewaySecurityProperties properties) {
        GatewaySecurityProperties.ApiKey apiKey = properties.apiKey();
        if (apiKey == null || apiKey.sha256Hashes() == null) {
            throw new IllegalStateException("API_KEY_SHA256_HASHES must contain at least one API key hash");
        }

        this.acceptedDigests = apiKey.sha256Hashes().stream()
                .filter(hash -> hash != null && !hash.isBlank())
                .map(String::trim)
                .map(ApiKeyValidator::parseDigest)
                .toList();

        if (acceptedDigests.isEmpty()) {
            throw new IllegalStateException("API_KEY_SHA256_HASHES must contain at least one API key hash");
        }
        if (apiKey.maxLength() < 1) {
            throw new IllegalStateException("gateway.security.api-key.max-length must be positive");
        }
        this.maxLength = apiKey.maxLength();
    }

    public boolean isValid(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > maxLength) {
            return false;
        }

        byte[] candidate = ApiKeyHasher.sha256(apiKey);
        boolean valid = false;
        for (byte[] acceptedDigest : acceptedDigests) {
            // Do not return early: always compare against every configured key.
            valid |= MessageDigest.isEqual(candidate, acceptedDigest);
        }
        return valid;
    }

    private static byte[] parseDigest(String hash) {
        if (hash.length() != SHA_256_HEX_LENGTH) {
            throw new IllegalStateException("Every API key hash must be a 64-character SHA-256 hex digest");
        }
        try {
            return HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("API key hashes must contain only hexadecimal characters", exception);
        }
    }
}
