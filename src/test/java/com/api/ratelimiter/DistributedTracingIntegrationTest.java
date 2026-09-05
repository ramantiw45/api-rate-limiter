package com.api.ratelimiter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class DistributedTracingIntegrationTest {

    @Autowired
    private Tracer tracer;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("Micrometer Tracer bean is configured and available in context")
    void tracerBean_isAvailable() {
        assertThat(tracer).isNotNull();
        Span span = tracer.nextSpan().name("test-span").start();
        try {
            assertThat(span.context().traceId()).isNotBlank();
            assertThat(span.context().spanId()).isNotBlank();
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("Inbound routed requests receive X-Request-ID and propagate correlation headers")
    void routedRequest_echoesCorrelationHeaders() {
        webTestClient.get()
                .uri("/echo/test-tracing")
                .header("X-Request-ID", "custom-trace-request-12345")
                .header("X-API-Key", "test-happy-key")
                .exchange()
                .expectHeader().valueEquals("X-Request-ID", "custom-trace-request-12345")
                .expectHeader().exists("X-Trace-ID");
    }

    @Test
    @DisplayName("Routed requests without X-Request-ID get auto-generated IDs")
    void routedRequestWithoutCorrelationId_generatesHeaders() {
        webTestClient.get()
                .uri("/echo/test-tracing")
                .header("X-API-Key", "test-happy-key")
                .exchange()
                .expectHeader().exists("X-Request-ID")
                .expectHeader().exists("X-Trace-ID");
    }
}
