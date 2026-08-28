package com.api.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void anonymousHealthCheck_isAvailableButDoesNotExposeDetails() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    @Test
    void authenticatedHealthCheck_exposesOperationalDetails() {
        webTestClient.get()
                .uri("/actuator/health")
                .headers(headers -> headers.setBasicAuth("test-actuator", "test-actuator-password"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components").exists();
    }

    @Test
    void metrics_withoutCredentials_returnsJsonUnauthorized() {
        webTestClient.get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"actuator\"")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    void metrics_withWrongCredentials_isRejected() {
        webTestClient.get()
                .uri("/actuator/metrics")
                .headers(headers -> headers.setBasicAuth("test-actuator", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void metrics_withActuatorCredentials_isAccessible() {
        webTestClient.get()
                .uri("/actuator/metrics")
                .headers(headers -> headers.setBasicAuth("test-actuator", "test-actuator-password"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void directFallbackInvocation_isDenied() {
        webTestClient.get()
                .uri("/fallback/echo")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withoutApiKey_isRejectedBeforeRouting() {
        webTestClient.get()
                .uri("/echo/anything")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"gateway\"")
                .expectHeader().exists("X-Request-ID")
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    void protectedRoute_withInvalidApiKey_isRejected() {
        webTestClient.get()
                .uri("/echo/anything")
                .header("X-API-Key", "not-a-valid-test-key")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withDuplicateApiKeyHeaders_isBadRequest() {
        webTestClient.get()
                .uri("/echo/anything")
                .header("X-API-Key", "test-happy-key", "test-timeout-key")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Bad Request");
    }
}
