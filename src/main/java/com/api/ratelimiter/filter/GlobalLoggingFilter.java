package com.api.ratelimiter.filter;

import com.api.ratelimiter.security.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li><b>Request-ID propagation</b> – reads {@code X-Request-ID} from the
 *       incoming request, or generates a fresh UUID if absent. The ID is
 *       forwarded downstream as a request header and echoed back to the caller
 *       as a response header (via {@link org.springframework.http.server.reactive.ServerHttpResponse#beforeCommit}).</li>
 *   <li><b>Structured access logging</b> – logs method, path, client identifier,
 *       HTTP status, and round-trip latency (ms) using {@code doFinally} so the
 *       log line is emitted regardless of whether the Mono completes normally,
 *       errors, or is cancelled.</li>
 * </ol>
 *
 * <p><b>Threading model</b>: all operations are non-blocking. The filter never
 * parks a thread; side effects are attached as reactive operators.
 */
@Component
public class GlobalLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GlobalLoggingFilter.class);

    private static final String REQUEST_ID_HEADER  = "X-Request-ID";
    private static final String API_KEY_HEADER     = "X-API-Key";

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

        // ── 3. Stamp request start time ──────────────────────────────────────
        final long startTime = System.currentTimeMillis();

        // ── 4. Forward X-Request-ID to the downstream service ────────────────
        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .build();

        // ── 5. Echo X-Request-ID back to the caller before response commit ───
        //      beforeCommit() fires synchronously just before the first write,
        //      so the header is always present even on 429 / error responses.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
            return Mono.empty();
        });

        // ── 6. Pre-filter log ─────────────────────────────────────────────────
        log.debug("[{}] --> {} {} | client={}",
                requestId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                clientId);

        // ── 7. Continue the filter chain, then log the completed response ─────
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signal -> {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    Integer statusCode = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : null;

                    log.info("[{}] <-- {} {} | client={} | status={} | latency={}ms | signal={}",
                            requestId,
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
