package com.api.ratelimiter.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Reactive {@link GlobalFilter} that intercepts HTTP 429 responses produced by the
 * {@code RequestRateLimiter} gateway filter and replaces the empty body with a
 * structured JSON error payload.
 *
 * <h3>How it works</h3>
 * <p>Spring Cloud Gateway's {@code RequestRateLimiterGatewayFilterFactory} calls
 * {@code exchange.getResponse().setComplete()} when a request is rejected — which
 * closes the connection without writing a body.  This filter wraps the
 * {@link ServerHttpResponse} in a {@link ServerHttpResponseDecorator} before
 * passing the exchange down the chain.  When the decorator's
 * {@link #setComplete()} is invoked and the response status is
 * {@link HttpStatus#TOO_MANY_REQUESTS}, it writes the JSON body via
 * {@link #writeWith(org.reactivestreams.Publisher)} instead of completing silently.
 *
 * <h3>Response on rate-limit breach</h3>
 * <pre>
 * HTTP/1.1 429 Too Many Requests
 * Content-Type: application/json
 * X-RateLimit-Remaining: 0
 * X-RateLimit-Replenish-Rate: 5
 * X-RateLimit-Burst-Capacity: 10
 * X-Request-ID: &lt;uuid&gt;
 *
 * {"error":"Too Many Requests","message":"Rate limit exceeded. Try again later."}
 * </pre>
 *
 * <h3>Order</h3>
 * <p>This filter uses order {@code Ordered.HIGHEST_PRECEDENCE + 1}, placing it
 * immediately after {@link GlobalLoggingFilter} (which runs at
 * {@code Ordered.HIGHEST_PRECEDENCE}).  Both run before any route-level
 * {@code GatewayFilter}s, ensuring the response decorator is already in place
 * when the rate-limiter filter executes.
 */
@Component
public class RateLimitErrorFilter implements GlobalFilter, Ordered {

    private static final byte[] RATE_LIMIT_BODY =
            "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Try again later.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    @Override
    public int getOrder() {
        // Must be lower than FilteringWebHandler (which wraps GatewayFilters),
        // and higher than GlobalLoggingFilter so logging still wraps everything.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse originalResponse = exchange.getResponse();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

            /**
             * Intercepts the silent "close without body" that the RequestRateLimiter
             * uses for 429 responses.  Delegates normally for every other status.
             *
             * <p>The {@link RedisRateLimiter} has already written the
             * {@code X-RateLimit-*} headers to the response before calling
             * {@code setComplete()}, so those headers survive intact; we only
             * inject {@code Content-Type} and the JSON body.
             */
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                    getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    getHeaders().setContentLength(RATE_LIMIT_BODY.length);

                    // writeWith() flushes the body AND commits the response,
                    // so we must NOT also call super.setComplete().
                    DataBuffer buffer = bufferFactory().wrap(RATE_LIMIT_BODY);
                    return writeWith(Mono.just(buffer));
                }
                return super.setComplete();
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }
}
