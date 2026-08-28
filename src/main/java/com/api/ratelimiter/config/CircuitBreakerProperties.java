package com.api.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe binding for the {@code gateway.circuit-breaker.*} properties
 * (see {@code application.yml}) that drive the {@code echoCircuitBreaker}
 * instance configured in {@link ResilienceConfig}.
 *
 * <p>Externalizing these as properties (rather than hard-coded constants)
 * allows per-environment tuning without a rebuild, and lets integration
 * tests override them to small values so timeout/circuit-opening scenarios
 * can be exercised in milliseconds instead of minutes.
 *
 * @param timeoutDurationMs             max time to wait for the downstream call
 *                                      before the {@code TimeLimiter} treats it
 *                                      as a failure.
 * @param slidingWindowSize             number of most-recent calls evaluated
 *                                      for the failure rate.
 * @param minimumNumberOfCalls          minimum calls required before the
 *                                      failure rate is evaluated at all.
 * @param failureRateThreshold          percentage (0-100) of failures within
 *                                      the window that trips the breaker OPEN.
 * @param waitDurationInOpenStateMs     how long the breaker stays OPEN
 *                                      (fast-failing every call) before
 *                                      allowing trial probes.
 * @param permittedCallsInHalfOpenState number of trial calls allowed through
 *                                      in HALF_OPEN state to decide recovery.
 */
@ConfigurationProperties(prefix = "gateway.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("4000") long timeoutDurationMs,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10000") long waitDurationInOpenStateMs,
        @DefaultValue("3") int permittedCallsInHalfOpenState) {
}
