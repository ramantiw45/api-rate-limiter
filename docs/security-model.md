# Security Architecture & Threat Model

This document outlines the zero-trust security architecture, credential lifecycle management, timing attack mitigations, and access controls implemented in the API Rate Limiter Gateway.

---

## 1. Zero-Trust API Key Architecture

Edge gateways frequently handle sensitive authentication tokens. Storing or handling plaintext keys exposes systems to accidental disclosure via access logs, memory dumps, and upstream leakage.

### Security Guarantees
1. **No Plaintext Storage**: The application configuration, environment variables, and memory space store **only cryptographic SHA-256 digests**, never raw API keys.
2. **Timing Attack Protection**: Key verification is performed in constant time using `MessageDigest.isEqual()` to prevent side-channel timing analysis.
3. **Upstream Credential Stripping**: Inbound `X-API-Key` headers are stripped by `ApiKeyValidationFilter` before dispatching requests to upstream microservices.
4. **Log Privacy & Fingerprinting**: Plaintext keys and complete 64-character SHA-256 digests are omitted from logs. A one-way 12-character truncated fingerprint (`key-fingerprint:0b2166513300`) is used for correlation and debugging.

---

## 2. Timing Attack Prevention

### The Vulnerability
Standard string comparison algorithms (e.g., `String.equals()` in Java) return `false` as soon as the first non-matching character is encountered. By measuring nanosecond-level response latencies across thousands of requests, an attacker can incrementally guess an API key character-by-character.

$$\text{Time}(\text{"secretKey"}, \text{"aaaaaaaa"}) < \text{Time}(\text{"secretKey"}, \text{"saaaaaaa"})$$

### The Defense
1. The gateway hashes the inbound API key:
   $$\text{digest} = \text{SHA-256}(\text{UTF-8}(X\text{-API-Key}))$$
2. The resulting 32-byte array is compared against each authorized byte array using `java.security.MessageDigest.isEqual(byte[] digesta, byte[] digestb)`.
3. `MessageDigest.isEqual()` executes in constant time:
   ```java
   public static boolean isEqual(byte[] digesta, byte[] digestb) {
       if (digesta == digestb) return true;
       if (digesta == null || digestb == null) return false;
       if (digesta.length != digestb.length) return false;

       int result = 0;
       for (int i = 0; i < digesta.length; i++) {
           result |= digesta[i] ^ digestb[i];
       }
       return result == 0;
   }
   ```
4. Even if an attacker passes candidate keys of varying lengths, hashing normalizes every input into an invariant 32-byte digest before constant-time evaluation.

---

## 3. Credential Lifecycle & Rotation

### Generating Authorized Key Digests
Keys are provisioned by hashing them externally before injecting them into the gateway environment:

```bash
# Generate a cryptographically secure random API key
openssl rand -hex 32

# Compute its SHA-256 hash
printf 'your-generated-api-key' | sha256sum
```

### Zero-Downtime Key Rotation
The gateway supports multiple comma-separated hashes in `API_KEY_SHA256_HASHES`. This enables seamless rotation:

```env
# Step 1: Active old key hash only
API_KEY_SHA256_HASHES=old_key_sha256_hash_here

# Step 2: Add new key hash alongside old key hash
API_KEY_SHA256_HASHES=old_key_sha256_hash_here,new_key_sha256_hash_here

# Step 3: Switch clients to the new API key

# Step 4: Revoke old key by removing its hash
API_KEY_SHA256_HASHES=new_key_sha256_hash_here
```

---

## 4. Actuator Security & Role-Based Access Control (RBAC)

Spring Boot Actuator endpoints provide powerful monitoring capabilities but expose attack surfaces if left unprotected.

```
Incoming Request
      │
      ├── Path: /actuator/health
      │      │
      │      ├── Anonymous Caller ──────► Expose aggregate: {"status":"UP"}
      │      │
      │      └── Authenticated Caller ──► Expose internal components (Redis, CircuitBreaker)
      │
      └── Path: /actuator/** (metrics, prometheus, gateway routes)
             │
             ├── Anonymous Caller ──────► HTTP 401 Unauthorized
             │
             └── Basic Auth (ROLE_ACTUATOR) ──► Access Granted
```

### Security Configuration Breakdown
Defined in [`SecurityConfig.java`](file:///C:/Users/Raman%20Tiwari/Web_Development/api-rate-limiter/src/main/java/com/api/ratelimiter/config/SecurityConfig.java):

```java
http
    .csrf(ServerHttpSecurity.CsrfSpec::disable)
    .cors(ServerHttpSecurity.CorsSpec::disable)
    .authorizeExchange(exchanges -> exchanges
        // Allow unauthenticated Kubernetes / load balancer health probes
        .pathMatchers(HttpMethod.GET, "/actuator/health").permitAll()
        // Require explicit ACTUATOR role for sensitive endpoints
        .pathMatchers("/actuator/**").hasRole("ACTUATOR")
        // All other routes handled by Gateway Filters
        .anyExchange().permitAll()
    )
    .httpBasic(Customizer.withDefaults());
```

---

## 5. Security Headers & Network Hygiene

Every response leaving the gateway is stamped with defensive security headers to mitigate common web application vulnerabilities:

- `X-Content-Type-Options: nosniff`: Prevents MIME-type sniffing.
- `X-Frame-Options: DENY`: Protects against clickjacking.
- `X-XSS-Protection: 0`: Modern defense against cross-site scripting audit bugs.
- `Referrer-Policy: no-referrer`: Prevents leaking sensitive URI structures in referrer headers.
- `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`: Ensures intermediary proxies and browsers never cache authenticated gateway responses.
