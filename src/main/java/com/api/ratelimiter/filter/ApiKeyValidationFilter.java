package com.api.ratelimiter.filter;

import com.api.ratelimiter.security.ApiKeyHasher;
import com.api.ratelimiter.security.ApiKeyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Authenticates requests to protected gateway routes using {@code X-API-Key}.
 *
 * <p>The filter runs before route-level filters, so unauthorized requests never
 * consume rate-limit tokens, touch the circuit breaker, or reach downstream.
 */
@Component
public class ApiKeyValidationFilter implements GlobalFilter, Ordered {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String VALIDATED_API_KEY_HASH_ATTRIBUTE =
            ApiKeyValidationFilter.class.getName() + ".validatedApiKeyHash";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyValidationFilter.class);
    private static final byte[] UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized\",\"message\":\"A valid X-API-Key header is required.\"}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] MALFORMED_BODY =
            "{\"error\":\"Bad Request\",\"message\":\"Exactly one X-API-Key header is required.\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final ApiKeyValidator apiKeyValidator;

    public ApiKeyValidationFilter(ApiKeyValidator apiKeyValidator) {
        this.apiKeyValidator = apiKeyValidator;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isProtectedPath(exchange.getRequest().getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }

        List<String> headerValues = exchange.getRequest().getHeaders().get(API_KEY_HEADER);
        if (headerValues != null && headerValues.size() > 1) {
            log.debug("Rejected request with multiple {} headers", API_KEY_HEADER);
            return writeError(exchange, HttpStatus.BAD_REQUEST, MALFORMED_BODY);
        }

        String apiKey = headerValues == null || headerValues.isEmpty() ? null : headerValues.getFirst();
        if (!apiKeyValidator.isValid(apiKey)) {
            log.debug("Rejected request with missing or invalid API key");
            return writeError(exchange, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
        }

        // Keep only a one-way identity for downstream gateway filters and strip
        // the credential itself so it cannot leak to the proxied service.
        exchange.getAttributes().put(
                VALIDATED_API_KEY_HASH_ATTRIBUTE, ApiKeyHasher.sha256Hex(apiKey));
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(API_KEY_HEADER))
                .build();
        return chain.filter(exchange.mutate().request(sanitizedRequest).build());
    }

    private static boolean isProtectedPath(String path) {
        return path.equals("/echo") || path.startsWith("/echo/");
    }

    private static Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, byte[] body) {
        exchange.getResponse().setStatusCode(status);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        headers.setContentLength(body.length);
        if (status == HttpStatus.UNAUTHORIZED) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"gateway\"");
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
