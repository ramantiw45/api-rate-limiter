package com.api.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Security settings supplied by the deployment environment.
 *
 * <p>API keys are configured only as lowercase or uppercase SHA-256 hex
 * digests. Plaintext API keys must never be stored in source control or
 * application configuration.
 */
@ConfigurationProperties(prefix = "gateway.security")
public record GatewaySecurityProperties(
        Actuator actuator,
        ApiKey apiKey) {

    public record Actuator(
            @DefaultValue("actuator") String username,
            String password) {
    }

    public record ApiKey(
            @DefaultValue List<String> sha256Hashes,
            @DefaultValue("512") int maxLength) {
    }
}
