package com.api.ratelimiter.controller;

import com.api.ratelimiter.config.CircuitBreakerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Internal handler invoked via {@code forward:/fallback/echo} whenever the
 * {@code echoCircuitBreaker} rejects a call — whether because the call timed
 * out, the downstream connection failed, or the circuit is currently OPEN.
 *
 * <p>Spring Cloud Gateway's {@code CircuitBreaker} filter resolves a
 * {@code forward:} fallback URI by dispatching internally to the standard
 * WebFlux {@code DispatcherHandler}, so this is a perfectly ordinary
 * {@code @RestController} endpoint — no gateway-specific wiring required.
 *
 * <p>Always returns {@code 503 Service Unavailable} with a structured JSON
 * body and a {@code Retry-After} header (RFC 9110 §10.2.3) hinting how long
 * the circuit is expected to remain open, so well-behaved clients can back off
 * intelligently instead of retrying immediately.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private final CircuitBreakerProperties circuitBreakerProperties;

    public FallbackController(CircuitBreakerProperties circuitBreakerProperties) {
        this.circuitBreakerProperties = circuitBreakerProperties;
    }

    @RequestMapping("/fallback/echo")
    public Mono<ResponseEntity<Map<String, String>>> echoFallback(ServerWebExchange exchange) {
        log.warn("Circuit breaker fallback triggered | path={} | method={}",
                exchange.getRequest().getPath(), exchange.getRequest().getMethod());

        long retryAfterSeconds = Math.max(1, circuitBreakerProperties.waitDurationInOpenStateMs() / 1000);

        Map<String, String> body = Map.of(
                "error", "Service Unavailable",
                "message", "The upstream echo service is temporarily unavailable. Please try again shortly."
        );

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }
}
