package com.api.ratelimiter;


import com.api.ratelimiter.config.RateLimiterConfig;
import com.api.ratelimiter.filter.GlobalLoggingFilter;
import com.api.ratelimiter.filter.RateLimitErrorFilter;
import com.api.ratelimiter.security.ApiKeyHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-loading smoke tests.
 *
 * <p><b>Prerequisites</b>: a running Redis instance on {@code localhost:6379}
 * (or override via env vars {@code REDIS_HOST} / {@code REDIS_PORT}).
 * For CI without Redis, start the full stack with {@code docker-compose up -d redis}
 * before running the test suite, or add the Testcontainers dependency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiRateLimiterApplicationTests {

    @Autowired
    private KeyResolver clientKeyResolver;

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private GlobalLoggingFilter globalLoggingFilter;

    @Autowired
    private RateLimitErrorFilter rateLimitErrorFilter;

    // ── 1. Spring context loads without errors ────────────────────────────────
    @Test
    void contextLoads() {
        assertThat(clientKeyResolver).isNotNull();
        assertThat(redisRateLimiter).isNotNull();
        assertThat(globalLoggingFilter).isNotNull();
        assertThat(rateLimitErrorFilter).isNotNull();
    }

    // ── 2. KeyResolver prefers X-API-Key header ───────────────────────────────
    @Test
    void keyResolver_resolvesApiKeyHeader() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/echo/test")
                .header("X-API-Key", "test-happy-key")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(clientKeyResolver.resolve(exchange))
                .expectNext(ApiKeyHasher.sha256Hex("test-happy-key"))
                .verifyComplete();
    }

    // ── 3. KeyResolver falls back to "unknown" when no IP is resolvable ───────
    @Test
    void keyResolver_fallsBackToUnknown_whenNoAddressAvailable() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/echo/test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // MockServerHttpRequest has no remote address by default → "unknown"
        StepVerifier.create(clientKeyResolver.resolve(exchange))
                .expectNextMatches(key -> key != null && !key.isBlank())
                .verifyComplete();
    }

    // ── 4. Rate-limiter bean uses correct defaults ────────────────────────────
    @Test
    void rateLimiter_configDefaults_areCorrect() {
        assertThat(RateLimiterConfig.REPLENISH_RATE).isEqualTo(5);
        assertThat(RateLimiterConfig.BURST_CAPACITY).isEqualTo(10);
        assertThat(RateLimiterConfig.REQUESTED_TOKENS).isEqualTo(1);
    }

    // ── 5. GlobalLoggingFilter has the highest order ──────────────────────────
    @Test
    void globalLoggingFilter_hasHighestPrecedence() {
        assertThat(globalLoggingFilter.getOrder())
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }

    // ── 6. RateLimitErrorFilter runs just after the logging filter ────────────
    @Test
    void rateLimitErrorFilter_runsJustAfterLoggingFilter() {
        assertThat(rateLimitErrorFilter.getOrder())
                .isEqualTo(globalLoggingFilter.getOrder() + 1);
    }
}
