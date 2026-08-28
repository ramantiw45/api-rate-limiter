package com.api.ratelimiter.config;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Redis-backed Token Bucket rate limiter for Spring Cloud Gateway.
 *
 * <h3>Algorithm – Token Bucket</h3>
 * <ul>
 *   <li><b>Replenish rate</b> – tokens added to the bucket per second (steady-state RPS).</li>
 *   <li><b>Burst capacity</b> – maximum tokens the bucket can hold; allows short bursts
 *       above the steady-state rate without being rejected.</li>
 *   <li><b>Requested tokens</b> – tokens consumed per request (usually 1).</li>
 * </ul>
 *
 * <p>The Lua script that performs the atomic increment/check lives inside the
 * {@code spring-cloud-starter-gateway} JAR at
 * {@code META-INF/scripts/request_rate_limiter.lua} and is executed on Redis as a
 * single atomic operation – safe for clustered deployments.
 *
 * <p>By declaring the bean here instead of relying solely on YAML, we gain a
 * typed, IDE-navigable home for all rate-limiter defaults. The per-route YAML
 * values ({@code redis-rate-limiter.*} under the filter args) take precedence for
 * individual routes and are applied via {@code ConfigurationService}.
 *
 * <p>Response headers automatically added by {@link RedisRateLimiter} when
 * {@code includeHeaders = true} (the default):
 * <ul>
 *   <li>{@code X-RateLimit-Remaining}</li>
 *   <li>{@code X-RateLimit-Replenish-Rate}</li>
 *   <li>{@code X-RateLimit-Burst-Capacity}</li>
 *   <li>{@code X-RateLimit-Requested-Tokens}</li>
 * </ul>
 */
@Configuration
public class RateLimiterConfig {

    /** Tokens added to the bucket per second (≈ steady-state allowed RPS). */
    public static final int REPLENISH_RATE = 5;

    /** Maximum tokens in the bucket; governs how large a burst is permitted. */
    public static final int BURST_CAPACITY = 10;

    /** Tokens deducted per request. */
    public static final int REQUESTED_TOKENS = 1;

    /**
     * Primary {@link RedisRateLimiter} bean.
     *
     * <p>Spring will auto-wire the {@code ReactiveStringRedisTemplate} and the
     * pre-registered Lua-script bean into the {@code @Autowired} fields of
     * {@link RedisRateLimiter} after construction (via
     * {@code AutowiredAnnotationBeanPostProcessor}). The {@code @ConditionalOnMissingBean}
     * guard in {@code GatewayRedisAutoConfiguration} ensures no duplicate bean is created.
     */
    @Bean
    public RedisRateLimiter redisRateLimiter() {
        RedisRateLimiter limiter = new RedisRateLimiter(REPLENISH_RATE, BURST_CAPACITY, REQUESTED_TOKENS);
        // Expose X-RateLimit-* headers on every response (already the default, made explicit).
        limiter.setIncludeHeaders(true);
        return limiter;
    }
}
