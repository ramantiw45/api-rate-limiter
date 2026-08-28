package com.api.ratelimiter.config;

import com.api.ratelimiter.security.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static com.api.ratelimiter.filter.ApiKeyValidationFilter.VALIDATED_API_KEY_HASH_ATTRIBUTE;

/**
 * Defines the strategy used to derive a unique rate-limit key per request.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code X-API-Key} request header – preferred for identified API consumers.</li>
 *   <li>Remote client IP address – used as an anonymous fallback.</li>
 *   <li>Literal {@code "unknown"} – last resort when no address is resolvable.</li>
 * </ol>
 *
 * <p>The bean name {@code clientKeyResolver} is referenced in {@code application.yml}
 * via the SpEL expression {@code #{@clientKeyResolver}}.
 */
@Configuration
public class KeyResolverConfig {

    private static final Logger log = LoggerFactory.getLogger(KeyResolverConfig.class);

    private static final String API_KEY_HEADER = "X-API-Key";

    @Bean
    @Primary
    public KeyResolver clientKeyResolver() {
        return exchange -> {
            // ── 1. Prefer the validated one-way identity ─────────────────────
            String validatedIdentity = exchange.getAttribute(VALIDATED_API_KEY_HASH_ATTRIBUTE);
            if (validatedIdentity != null) {
                return Mono.just(validatedIdentity);
            }

            // Direct resolver usage and future unprotected routes still support
            // the header, but return only its hash as the Redis identity.
            String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
            if (apiKey != null && !apiKey.isBlank()) {
                String hashedKey = ApiKeyHasher.sha256Hex(apiKey);
                log.debug("Rate-limit key resolved from API key fingerprint {}",
                        ApiKeyHasher.fingerprint(apiKey));
                return Mono.just(hashedKey);
            }

            // ── 2. Fall back to the remote client IP address ────────────────
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress != null && remoteAddress.getAddress() != null) {
                String ip = remoteAddress.getAddress().getHostAddress();
                log.debug("Rate-limit key resolved from remote IP: {}", ip);
                return Mono.just(ip);
            }

            // ── 3. Last resort ──────────────────────────────────────────────
            log.warn("Could not resolve rate-limit key; falling back to 'unknown'");
            return Mono.just("unknown");
        };
    }
}
