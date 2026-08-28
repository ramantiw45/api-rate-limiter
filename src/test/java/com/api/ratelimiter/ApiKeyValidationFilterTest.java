package com.api.ratelimiter;

import com.api.ratelimiter.config.GatewaySecurityProperties;
import com.api.ratelimiter.filter.ApiKeyValidationFilter;
import com.api.ratelimiter.security.ApiKeyHasher;
import com.api.ratelimiter.security.ApiKeyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyValidationFilterTest {

    private static final String VALID_KEY = "unit-test-api-key";

    private ApiKeyValidationFilter filter;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties(
                new GatewaySecurityProperties.Actuator("actuator", "password"),
                new GatewaySecurityProperties.ApiKey(
                        List.of(ApiKeyHasher.sha256Hex(VALID_KEY)), 32));
        filter = new ApiKeyValidationFilter(new ApiKeyValidator(properties));
    }

    @Test
    void validKey_continuesChainWithHashedIdentityAndStripsCredential() {
        AtomicBoolean invoked = new AtomicBoolean();
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        MockServerWebExchange exchange = exchange("/echo/test", VALID_KEY);
        GatewayFilterChain chain = forwarded -> Mono.fromRunnable(() -> {
            forwardedExchange.set(forwarded);
            invoked.set(true);
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(invoked).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedExchange.get().getRequest().getHeaders())
                .doesNotContainKey(ApiKeyValidationFilter.API_KEY_HEADER);
        String rateLimitIdentity = forwardedExchange.get().getAttribute(
                ApiKeyValidationFilter.VALIDATED_API_KEY_HASH_ATTRIBUTE);
        assertThat(rateLimitIdentity).isEqualTo(ApiKeyHasher.sha256Hex(VALID_KEY));
    }

    @Test
    void missingKey_returnsUnauthorizedWithoutInvokingChain() {
        AtomicBoolean invoked = new AtomicBoolean();
        MockServerWebExchange exchange = exchange("/echo/test");

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.fromRunnable(() -> invoked.set(true))))
                .verifyComplete();

        assertThat(invoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .expectNext("{\"error\":\"Unauthorized\",\"message\":\"A valid X-API-Key header is required.\"}")
                .verifyComplete();
    }

    @Test
    void duplicateHeaders_returnBadRequest() {
        MockServerWebExchange exchange = exchange("/echo/test", VALID_KEY, VALID_KEY);

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oversizedKey_isRejectedBeforeHashingWorkCanGrowUnbounded() {
        MockServerWebExchange exchange = exchange("/echo/test", "x".repeat(33));

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unprotectedPath_doesNotRequireApiKey() {
        AtomicBoolean invoked = new AtomicBoolean();
        MockServerWebExchange exchange = exchange("/actuator/health");

        StepVerifier.create(filter.filter(exchange, ignored -> Mono.fromRunnable(() -> invoked.set(true))))
                .verifyComplete();

        assertThat(invoked).isTrue();
    }

    private static MockServerWebExchange exchange(String path, String... apiKeys) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (apiKeys.length > 0) {
            request.header("X-API-Key", apiKeys);
        }
        return MockServerWebExchange.from(request.build());
    }
}
