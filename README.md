# API Rate Limiter Gateway

Reactive Spring Cloud Gateway backed by Redis rate limiting and Resilience4j.

## Local startup

```bash
docker compose up --build -d
```

The Compose defaults are intended only for local development:

- API key: `local-development-key`
- Actuator username: `actuator`
- Actuator password: `change-me-local-only`

For any shared environment, copy `.env.example` to `.env`, replace the
credentials, and configure only SHA-256 hashes of API keys.

## Requests

```bash
curl -H "X-API-Key: local-development-key" http://localhost:8080/echo/get
curl http://localhost:8080/actuator/health
curl -u actuator:change-me-local-only http://localhost:8080/actuator/metrics
```

Anonymous health requests expose only aggregate status. Actuator details,
metrics, gateway routes, Prometheus output, and circuit-breaker events require
HTTP Basic authentication.

## Generate an API-key hash

```bash
printf 'your-api-key' | sha256sum
```

Set one or more comma-separated hashes through `API_KEY_SHA256_HASHES`. The
gateway validates keys in constant time, uses the full hash as the Redis
rate-limit identity, and logs only a short hash fingerprint.
