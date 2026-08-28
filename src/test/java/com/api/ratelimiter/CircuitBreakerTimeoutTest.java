package com.api.ratelimiter;

import com.api.ratelimiter.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Resilience4j Circuit Breaker + TimeLimiter protection wrapping
 * the {@code echo-route}.
 *
 * <p>A local, fully-controlled Reactor Netty server stands in for the real
 * downstream ({@code httpbin.org}) so timeout and failure scenarios are
 * deterministic and fast, instead of depending on real network conditions.
 * The route's {@code uri} is overridden via the {@code ECHO_UPSTREAM_URI}
 * property (see {@code application.yml}) to point at this local server.
 *
 * <p>Circuit breaker thresholds are overridden to small values purely so this
 * suite runs in seconds rather than minutes; production defaults (see
 * {@code application.yml}) are more conservative.
 *
 * <p>The Redis rate-limiter boundary is mocked in this suite so circuit-breaker
 * behavior is deterministic and does not depend on developer infrastructure.
 * Dedicated rate-limiter integration is covered separately.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Keep the Netty-level timeout generous so the Resilience4j
                // TimeLimiter (tuned below) is deterministically the one that fires.
                "spring.cloud.gateway.httpclient.response-timeout=30s",
                "spring.cloud.gateway.httpclient.connect-timeout=2000",

                // Small, fast thresholds — purely to keep this test suite quick.
                "gateway.circuit-breaker.timeout-duration-ms=800",
                "gateway.circuit-breaker.sliding-window-size=4",
                "gateway.circuit-breaker.minimum-number-of-calls=4",
                "gateway.circuit-breaker.failure-rate-threshold=50",
                "gateway.circuit-breaker.wait-duration-in-open-state-ms=1500",
                "gateway.circuit-breaker.permitted-calls-in-half-open-state=2"
        })
@AutoConfigureWebTestClient(timeout = "10s")
@ActiveProfiles("test")
@Import(CircuitBreakerTimeoutTest.TestRateLimiterConfiguration.class)
class CircuitBreakerTimeoutTest {

    private static final String WARMUP_API_KEY = "test-warmup-key";
    private static final String HAPPY_API_KEY = "test-happy-key";
    private static final String TIMEOUT_API_KEY = "test-timeout-key";
    private static final String OPEN_API_KEY = "test-open-key";
    private static final String RECOVERY_API_KEY = "test-recovery-key";
    private static final String RATE_LIMIT_API_KEY = "test-rate-limit-key";

    private static DisposableServer mockUpstream;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TestRedisRateLimiter redisRateLimiter;

    /**
     * Starts a local HTTP server that stands in for the real downstream.
     * Any request path containing "slow" is delayed 5s (well beyond the
     * 800ms test timeout above); everything else responds instantly.
     *
     * <p>Runs before Spring's context is prepared, so {@code mockUpstream}
     * is guaranteed to be bound before {@link #registerUpstreamUri} reads its
     * port — the same pattern used by Testcontainers + {@code @DynamicPropertySource}.
     */
    @BeforeAll
    static void startMockUpstream() {
        mockUpstream = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    String uri = request.uri();
                    if (uri.contains("slow")) {
                        return response.status(200)
                                .sendString(Mono.just("{\"slow\":true}").delayElement(Duration.ofSeconds(5)));
                    }
                    return response.status(200).sendString(Mono.just("{\"fast\":true}"));
                })
                .bindNow();
    }

    @AfterAll
    static void stopMockUpstream() {
        if (mockUpstream != null) {
            mockUpstream.disposeNow();
        }
    }

    @DynamicPropertySource
    static void registerUpstreamUri(DynamicPropertyRegistry registry) {
        registry.add("ECHO_UPSTREAM_URI", () -> "http://localhost:" + mockUpstream.port());
    }

    /**
     * Guarantees a clean slate before every test:
     * <ol>
     *   <li>Fires one warm-up request through the real gateway route so the
     *       {@code echoCircuitBreaker} instance is lazily created (by the
     *       actual {@code CircuitBreaker} filter) using OUR custom
     *       Resilience4j config — calling {@code registry.circuitBreaker(name)}
     *       before that first real request would instead create it with
     *       Resilience4j's built-in defaults, silently defeating our tuning.</li>
     *   <li>Resets it to a clean CLOSED state with empty metrics, so tests are
     *       independent of each other and of execution order.</li>
     * </ol>
     */
    @BeforeEach
    void warmUpAndResetCircuitBreaker() {
        redisRateLimiter.allowAll();

        webTestClient.get()
                .uri("/echo/warmup-" + UUID.randomUUID())
                .header("X-API-Key", WARMUP_API_KEY)
                .exchange();

        circuitBreakerRegistry.circuitBreaker(ResilienceConfig.ECHO_CIRCUIT_BREAKER_NAME).reset();
    }

    // ── Regression: CB wrapping must not break the normal happy path ──────────
    @Test
    void fastUpstream_passesThroughNormally() {
        webTestClient.get()
                .uri("/echo/happy")
                .header("X-API-Key", HAPPY_API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.fast").isEqualTo(true);
    }

    // ── Core case: a hung downstream must not hang the gateway ────────────────
    @Test
    void slowUpstream_exceedsTimeout_returnsFallbackWithinBoundedTime() {
        Instant start = Instant.now();

        webTestClient.get()
                .uri("/echo/slow")
                .header("X-API-Key", TIMEOUT_API_KEY)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After")
                .expectBody()
                .jsonPath("$.error").isEqualTo("Service Unavailable");

        Duration elapsed = Duration.between(start, Instant.now());

        // The mock upstream sleeps for 5s; if we'd waited for it, this would
        // fail. The 800ms TimeLimiter must cut it off well before that.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    // ── Once broken, stop hammering the downstream — fail fast instead ────────
    @Test
    void repeatedTimeouts_openTheCircuit_thenFailsFastWithoutCallingUpstream() {
        // minimumNumberOfCalls=4, failureRateThreshold=50%: 4 consecutive
        // timeouts is a 100% failure rate over the window -> circuit opens.
        for (int i = 0; i < 4; i++) {
            webTestClient.get()
                    .uri("/echo/slow")
                    .header("X-API-Key", OPEN_API_KEY)
                    .exchange()
                    .expectStatus().isEqualTo(503);
        }

        // Circuit should now be OPEN. This 5th call must fail IMMEDIATELY —
        // Resilience4j short-circuits before even attempting the network call,
        // instead of waiting out the 800ms timeout again.
        Instant start = Instant.now();

        webTestClient.get()
                .uri("/echo/slow")
                .header("X-API-Key", OPEN_API_KEY)
                .exchange()
                .expectStatus().isEqualTo(503);

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
    }

    // ── Self-healing: the breaker must recover once the downstream is healthy ─
    @Test
    void afterWaitDuration_halfOpenProbeSucceeds_circuitCloses() throws InterruptedException {
        // Open the circuit with 4 timeouts.
        for (int i = 0; i < 4; i++) {
            webTestClient.get()
                    .uri("/echo/slow")
                    .header("X-API-Key", RECOVERY_API_KEY)
                    .exchange()
                    .expectStatus().isEqualTo(503);
        }

        // Wait past waitDurationInOpenStateMs (1500ms) so Resilience4j
        // auto-transitions OPEN -> HALF_OPEN.
        Thread.sleep(1800);

        // permittedCallsInHalfOpenState=2: send 2 successful probe calls
        // against the (still-healthy) fast path.
        for (int i = 0; i < 2; i++) {
            webTestClient.get()
                    .uri("/echo/happy")
                    .header("X-API-Key", RECOVERY_API_KEY)
                    .exchange()
                    .expectStatus().isOk();
        }

        // Circuit should now be CLOSED again — normal traffic flows.
        webTestClient.get()
                .uri("/echo/happy")
                .header("X-API-Key", RECOVERY_API_KEY)
                .exchange()
                .expectStatus().isOk();
    }

    // ── Special case: rate-limit rejections must never count as CB failures ──
    @Test
    void rateLimiterRejections_doNotCountAsCircuitBreakerFailures() throws Exception {
        int totalRequests = 20;
        AtomicInteger attempts = new AtomicInteger();
        String rateLimitIdentity = com.api.ratelimiter.security.ApiKeyHasher
                .sha256Hex(RATE_LIMIT_API_KEY);

        // Deterministically allow 10 requests and reject the rest at the
        // RedisRateLimiter boundary, independent of a local Redis process.
        redisRateLimiter.respondWith(identity -> new RateLimiter.Response(
                !identity.equals(rateLimitIdentity) || attempts.incrementAndGet() <= 10,
                Map.of()));

        // Concurrent burst, past the configured allowance, so some requests
        // are rejected by the RATE LIMITER — not the CB.
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < totalRequests; i++) {
                futures.add(executor.submit(() -> webTestClient.get()
                        .uri("/echo/happy")
                        .header("X-API-Key", RATE_LIMIT_API_KEY)
                        .exchange()
                        .returnResult(Void.class)
                        .getStatus()
                        .value()));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(10, TimeUnit.SECONDS));
            }

            // Only 200 (allowed) or 429 (rate-limited) are valid outcomes.
            assertThat(statuses).allMatch(status -> status == 200 || status == 429);
            // Prove the burst actually triggered rate limiting...
            assertThat(statuses).contains(429);
            // ...and prove the circuit breaker was never involved: the
            // RequestRateLimiter filter runs BEFORE CircuitBreaker and
            // short-circuits with 429 before the CB-wrapped call is attempted.
            assertThat(statuses).doesNotContain(503);
        } finally {
            executor.shutdown();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestRateLimiterConfiguration {

        @Bean
        @Primary
        TestRedisRateLimiter testRedisRateLimiter() {
            return new TestRedisRateLimiter();
        }
    }

    static final class TestRedisRateLimiter extends RedisRateLimiter {

        private volatile Function<String, RateLimiter.Response> responseFactory;

        private TestRedisRateLimiter() {
            super(5, 10, 1);
            allowAll();
        }

        @Override
        public Mono<RateLimiter.Response> isAllowed(String routeId, String id) {
            return Mono.just(responseFactory.apply(id));
        }

        private void allowAll() {
            respondWith(ignored -> new RateLimiter.Response(true, Map.of()));
        }

        private void respondWith(Function<String, RateLimiter.Response> responseFactory) {
            this.responseFactory = responseFactory;
        }
    }
}
