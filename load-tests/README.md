# Automated Load Testing Suite (k6)

This directory contains automated performance and resilience benchmarks using [Grafana k6](https://k6.io/). The tests validate token replenishment accuracy, burst handling, zero-trust authentication under load, and circuit breaker trip/recovery state transitions.

---

## 🚀 Running the Benchmarks

All tests are containerized through Docker Compose. You do **not** need to install k6 on your local host.

### 1. Burst Capacity & Rate Limiting Benchmark
Tests the Redis Token Bucket burst capacity by firing 25 concurrent requests in a 1-second burst window.
- **Expected Outcome**: Exactly 10 requests succeed (`200 OK`) and the remaining 15 requests are rejected with `429 Too Many Requests`.
- **Command**:
  ```bash
  docker compose --profile load-test run --rm k6 run /scripts/rate-limit-burst.js
  ```

---

### 2. Steady-State Refill Benchmark
Simulates a steady arrival rate of 4 requests/second for 15 seconds (matching the gateway's steady-state token refill rate of 5 tokens/second).
- **Expected Outcome**: Zero 429 rejections (`steady_error_rate = 0.0%`). Confirms that the token bucket continuously refills without degrading client traffic.
- **Command**:
  ```bash
  docker compose --profile load-test run --rm k6 run /scripts/steady-state-refill.js
  ```

---

### 3. Circuit Breaker Lifecycle Benchmark
Tests the 4-phase Resilience4j state machine under upstream latency degradation:
1. **Phase 1 (Tripping)**: Sends 5 slow requests (>4s duration). The gateway's `TimeLimiter` interrupts them at 4,000ms.
2. **Phase 2 (Fast-Fail)**: With the circuit breaker now `OPEN`, subsequent requests fast-fail in **sub-20ms** (no network wait), returning structured 503 fallback responses.
3. **Phase 3 (Cooling Period)**: Waits 11 seconds for the 10-second `wait-duration-in-open-state` to elapse.
4. **Phase 4 (Recovery)**: Verifies that trial calls in `HALF_OPEN` succeed and automatically reset the circuit back to `CLOSED` (`200 OK`).
- **Command**:
  ```bash
  docker compose --profile load-test run --rm k6 run /scripts/circuit-breaker-trip.js
  ```

---

## 📊 Environment Variables

You can customize the target URL and API key when invoking k6:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `GATEWAY_URL` | `http://gateway:8080` | URL of the API gateway instance. |
| `API_KEY` | `my-secure-api-key` | Raw API key sent in the `X-API-Key` header. |

Example overriding target:
```bash
docker compose --profile load-test run --rm -e GATEWAY_URL=http://gateway:8080 -e API_KEY=custom-key k6 run /scripts/rate-limit-burst.js
```
