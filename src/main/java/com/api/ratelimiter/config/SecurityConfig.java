package com.api.ratelimiter.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reactive Spring Security configuration.
 *
 * <p>The liveness/readiness health endpoint remains public for container and
 * orchestrator probes, but its component details are hidden from anonymous
 * callers by Actuator configuration. Every other Actuator endpoint requires
 * HTTP Basic authentication and the {@code ACTUATOR} role.
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public MapReactiveUserDetailsService actuatorUsers(
            GatewaySecurityProperties properties,
            PasswordEncoder passwordEncoder) {

        GatewaySecurityProperties.Actuator actuator = properties.actuator();
        if (actuator == null || actuator.username() == null || actuator.username().isBlank()
                || actuator.password() == null || actuator.password().isBlank()) {
            throw new IllegalStateException(
                    "ACTUATOR_USERNAME and ACTUATOR_PASSWORD must be configured and non-blank");
        }

        UserDetails user = User.withUsername(actuator.username())
                .password(passwordEncoder.encode(actuator.password()))
                .roles("ACTUATOR")
                .build();

        return new MapReactiveUserDetailsService(user);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ServerAuthenticationEntryPoint authenticationEntryPoint,
            ServerAccessDeniedHandler accessDeniedHandler) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ACTUATOR")
                        // The circuit-breaker forward is an internal dispatch. A direct
                        // external request to this controller must never be accepted.
                        .pathMatchers("/fallback/**").denyAll()
                        .anyExchange().permitAll())
                .build();
    }

    @Bean
    public ServerAuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (exchange, exception) -> writeSecurityError(
                exchange.getResponse(), objectMapper, HttpStatus.UNAUTHORIZED,
                "Unauthorized", "Authentication is required to access this endpoint.")
                .doFirst(() -> exchange.getResponse().getHeaders()
                        .set("WWW-Authenticate", "Basic realm=\"actuator\""));
    }

    @Bean
    public ServerAccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (exchange, exception) -> writeSecurityError(
                exchange.getResponse(), objectMapper, HttpStatus.FORBIDDEN,
                "Forbidden", "Access to this endpoint is denied.");
    }

    private static Mono<Void> writeSecurityError(
            org.springframework.http.server.reactive.ServerHttpResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String error,
            String message) {

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(Map.of("error", error, "message", message));
        } catch (JsonProcessingException serializationError) {
            body = ("{\"error\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().setContentLength(body.length);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
