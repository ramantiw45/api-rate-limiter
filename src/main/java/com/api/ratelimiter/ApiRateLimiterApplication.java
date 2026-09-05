package com.api.ratelimiter;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class ApiRateLimiterApplication {

    @PostConstruct
    public void init() {
        // Enable automatic context propagation across Project Reactor operators
        // for Micrometer Tracing (preserves TraceContext/MDC across Netty thread switches)
        Hooks.enableAutomaticContextPropagation();
    }

    public static void main(String[] args) {
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(ApiRateLimiterApplication.class, args);
    }
}
