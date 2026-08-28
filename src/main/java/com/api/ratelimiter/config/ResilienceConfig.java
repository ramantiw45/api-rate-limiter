package com.api.ratelimiter.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures the Resilience4j Circuit Breaker + TimeLimiter that protects the
 * {@code echo-route} from a slow or unhealthy downstream.
 *
 * <h3>Why this exists</h3>
 * <p>Without this, a hung or slow {@code httpbin.org} (or whatever sits behind
 * a route) can tie up Netty event-loop threads indefinitely, starving the
 * entire gateway — a single flaky downstream becomes a gateway-wide outage.
 * This class adds two layers of defense for the route named {@code
 * echoCircuitBreaker} (referenced from the {@code CircuitBreaker} filter in
 * {@code application.yml}):
 *
 * <ol>
 *   <li><b>TimeLimiter</b> — bounds how long any single call is allowed to
 *       run before it's treated as a failure and the fallback is invoked.</li>
 *   <li><b>CircuitBreaker</b> — after enough failures within a rolling
 *       window, stops even attempting the call for a cool-down period,
 *       returning the fallback immediately (protects the event loop from
 *       repeatedly waiting out the timeout against a downstream that's known
 *       to be unhealthy).</li>
 * </ol>
 *
 * <h3>Important scoping note</h3>
 * <p>The circuit breaker is keyed by <b>route</b> (the {@code name} argument
 * in the {@code CircuitBreaker} filter, {@code "echoCircuitBreaker"}), not by
 * individual downstream path. If the breaker opens, <em>all</em> traffic on
 * {@code /echo/**} fast-fails during the open window — not just the specific
 * sub-path that caused the failures. This is intentional: the breaker
 * protects the health of the entire downstream dependency, which is usually
 * the right granularity (a struggling host is struggling for all paths).
 *
 * <h3>What counts as a failure</h3>
 * <p>By default, Resilience4j's {@link CircuitBreakerConfig} records
 * <em>any</em> thrown exception as a failure (connection refused, timeout,
 * premature close, etc.) — including the {@code TimeoutException} raised by
 * the {@code TimeLimiter} above. Plain HTTP error status codes (4xx/5xx)
 * proxied back from a reachable downstream are <em>not</em> treated as
 * failures, since the reactive call itself still completes normally; only a
 * broken/slow connection trips this breaker. Extending it to also treat
 * specific status codes as failures is a reasonable future enhancement, not
 * implemented here to keep this change focused.
 *
 * @see CircuitBreakerProperties
 * @see com.api.ratelimiter.controller.FallbackController
 */
@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class ResilienceConfig {

    /**
     * Name shared with the {@code CircuitBreaker} filter's {@code name} arg
     * in {@code application.yml}, and used to look up / reset this specific
     * breaker instance (e.g. in tests, or via the {@code CircuitBreakerRegistry}).
     */
    public static final String ECHO_CIRCUIT_BREAKER_NAME = "echoCircuitBreaker";

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> echoCircuitBreakerCustomizer(
            CircuitBreakerProperties properties) {

        return factory -> factory.configure(builder -> builder
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(properties.timeoutDurationMs()))
                        // Best-effort cancel the in-flight call once we give up on it,
                        // freeing the underlying connection instead of leaking it.
                        .cancelRunningFuture(true)
                        .build())
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(properties.slidingWindowSize())
                        .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                        .failureRateThreshold(properties.failureRateThreshold())
                        .waitDurationInOpenState(Duration.ofMillis(properties.waitDurationInOpenStateMs()))
                        .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build()),
                ECHO_CIRCUIT_BREAKER_NAME);
    }
}
