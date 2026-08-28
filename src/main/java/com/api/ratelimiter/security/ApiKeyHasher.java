package com.api.ratelimiter.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One-way API-key hashing utility. Hashes are safe to use as Redis rate-limit
 * identities and log fingerprints; plaintext credentials are never retained.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    public static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    public static String fingerprint(String value) {
        return sha256Hex(value).substring(0, 12);
    }
}
