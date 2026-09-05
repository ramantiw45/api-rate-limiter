package com.api.ratelimiter.filter;

import com.api.ratelimiter.security.ApiKeyHasher;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Reactive global filter that runs at the <em>highest</em> precedence, wrapping
 * every inbound request and outbound response passing through the gateway.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li><b>Distributed Tracing & Correlation</b> – extracts active distributed
 *       trace ID from Micrometer Tracing, tags the current span with correlation
 *       attributes (client ID, request ID), propagates {@code X-Request-ID} downstream,
 *       and echoes both {@code X-Request-ID} and {@code X-Trace-ID} back to the caller.</li>
 *   <li><b>Structured access logging</b> – logs method, path, client identifier,
 *       correlation ID, HTTP status, and round-trip latency (ms) using {@code doFinally}.</li>
 * </ol>
 *
 * <p><b>Threading model</b>: all operations are non-blocking. The filter never
 * parks a thread; side effects are attached as reactive operators.
 */
@Component
public class GlobalLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalLoggingFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String TRACE_ID_HEADER   = "X-Trace-ID";
    private static final String API_KEY_HEADER   = "X-API-Key";

    private final ObjectProvider<Tracer> tracerProvider;

    public GlobalLoggingFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    // Runs before every built-in GlobalFilter, including RouteToRequestUrlFilter (10000)
    // and the FilteringWebHandler that executes per-route GatewayFilters.
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // ── 1. Resolve (or generate) the correlation ID ──────────────────────
        String incomingId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        final String requestId = (incomingId != null && !incomingId.isBlank())
                ? incomingId
                : UUID.randomUUID().toString();

        // ── 2. Identify the client (mirrors KeyResolver precedence) ──────────
        final String clientId = resolveClientId(exchange);

        // ── 3. Resolve active trace span & ID ────────────────────────────────
        Tracer tracer = tracerProvider.getIfAvailable();
        Span currentSpan = tracer != null ? tracer.currentSpan() : null;
        final String traceId = (currentSpan != null && currentSpan.context() != null)
                ? currentSpan.context().traceId()
                : null;

        if (currentSpan != null) {
            currentSpan.tag("gateway.request_id", requestId);
            currentSpan.tag("gateway.client_id", clientId);
        }

        // ── 4. Stamp request start time ──────────────────────────────────────
        final long startTime = System.currentTimeMillis();

        // ── 5. Forward X-Request-ID to the downstream service ────────────────
        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .build();

        // ── 6. Echo correlation headers back to caller before response commit ─
        //      beforeCommit() fires synchronously just before the first write,
        //      so the headers are always present even on 429 / error responses.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
            if (traceId != null && !traceId.isBlank()) {
                exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
            }
            return Mono.empty();
        });

        // ── 7. Pre-filter log ────────────────────────────────────────────────
        final String logContext = (traceId != null && !traceId.isBlank())
                ? (requestId + " | trace:" + traceId)
                : requestId;

        log.debug("[{}] --> {} {} | client={}",
                logContext,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                clientId);

        // ── 8. Continue the filter chain, then log the completed response ────
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signal -> {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    Integer statusCode = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : null;

                    if (currentSpan != null && statusCode != null) {
                        currentSpan.tag("http.status_code", String.valueOf(statusCode));
                    }

                    log.info("[{}] <-- {} {} | client={} | status={} | latency={}ms | signal={}",
                            logContext,
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath().value(),
                            clientId,
                            statusCode,
                            latencyMs,
                            signal);
                });
    }

    /**
     * Resolves the human-readable client identifier used in log lines.
     * Mirrors the logic in {@link com.api.ratelimiter.config.KeyResolverConfig}.
     */
    private String resolveClientId(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key-fingerprint:" + ApiKeyHasher.fingerprint(apiKey);
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return "ip:" + remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
