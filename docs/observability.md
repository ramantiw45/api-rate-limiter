# Production Observability Stack: Prometheus & Grafana

This document details the architecture, metric instrumentation, automated provisioning, and visual telemetry dashboards for the **API Rate Limiter & Edge Gateway**.

---

## 1. Architecture Overview

The gateway exports low-overhead telemetry at runtime across three complementary observability pillars:

```mermaid
flowchart TD
    Client["Client / Load Generator (k6)"] -->|Requests| Gateway["Spring Cloud Gateway (:8080)"]
    Gateway -->|Forward| Upstream["Downstream Backend (httpbin.org)"]

    subgraph Metrics & Dashboards
        Gateway -->|Actuator /actuator/prometheus| Prom["Prometheus Time-Series DB (:9090)"]
        Prom -->|Datasource Proxy (PromQL)| Grafana["Grafana Telemetry Dashboard (:3000)"]
    end

    subgraph Distributed Tracing
        Gateway -->|OpenTelemetry Spans| Zipkin["Zipkin Distributed Tracing (:9411)"]
    end
```

| Service | Port | Default Credentials | Purpose |
| :--- | :--- | :--- | :--- |
| **Spring Cloud Gateway** | `8080` | `actuator / change-me-local-only` | Edge reverse proxy & token bucket engine |
| **Prometheus** | `9090` | None | Time-series scraper (2-second interval) |
| **Grafana** | `3000` | `admin / admin` | Auto-provisioned real-time visualization |
| **Zipkin** | `9411` | None | End-to-end distributed span tracing |

---

## 2. Metric Instrumentation & PromQL Reference

The gateway publishes metrics to `/actuator/prometheus` protected by HTTP Basic Authentication. Prometheus is configured to poll this endpoint every 2 seconds.

### Key Metrics Dictionary

| Metric Name | Type | Description |
| :--- | :--- | :--- |
| `http_server_requests_seconds_count` | Summary | Total inbound HTTP requests partitioned by `method`, `status`, and `uri`. |
| `http_server_requests_seconds_bucket` | Histogram | SLA duration buckets (50ms, 100ms, 250ms, 500ms, 1s, 4s) for quantile calculations. |
| `http_client_requests_seconds_sum / count` | Summary | Latency incurred strictly between the Gateway and upstream backend. |
| `resilience4j_circuitbreaker_state` | Gauge | State indicator for each circuit (`closed`, `open`, `half_open`). |
| `resilience4j_circuitbreaker_failure_rate` | Gauge | Calculated failure percentage across the sliding window. |
| `jvm_memory_used_bytes` | Gauge | Heap and non-heap memory allocated by the Generational ZGC runtime. |

### PromQL Production Queries

#### 1. Rate Limiter Drop Rate (429 Rejections per second)
```promql
sum(rate(http_server_requests_seconds_count{status="429"}[1m])) or vector(0)
```

#### 2. Inbound Throughput by HTTP Status Code
```promql
sum(rate(http_server_requests_seconds_count[30s])) by (status)
```

#### 3. Gateway Ingress Latency Percentiles (p95 & p99)
```promql
# p95 latency in milliseconds (excluding actuator health checks)
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri!="/actuator/health"}[30s])) by (le)) * 1000

# p99 latency in milliseconds
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri!="/actuator/health"}[30s])) by (le)) * 1000
```

#### 4. Circuit Breaker Discrete State
Maps boolean gauge flags into a unified categorical scale: `1 = CLOSED`, `2 = HALF_OPEN`, `3 = OPEN`:
```promql
resilience4j_circuitbreaker_state{name="echoCircuitBreaker",state="closed"} * 1 
+ resilience4j_circuitbreaker_state{name="echoCircuitBreaker",state="half_open"} * 2 
+ resilience4j_circuitbreaker_state{name="echoCircuitBreaker",state="open"} * 3
```

---

## 3. Pre-Provisioned Grafana Dashboard

Grafana is provisioned as code upon startup (`docker/grafana/provisioning/`). No manual clicking or configuration is required.

### Dashboard UID: `gateway-overview`
**Access URL**: [http://localhost:3000/d/gateway-overview/api-rate-limiter-and-gateway-overview](http://localhost:3000/d/gateway-overview/api-rate-limiter-and-gateway-overview)

The dashboard is structured into 4 thematic operational rows:

1. **Gateway Health & Resilience Summary**:
   - **Gateway Status**: Green `UP` / Red `DOWN` binary probe indicator.
   - **Circuit Breaker State**: Real-time categorical card (`CLOSED`, `HALF_OPEN`, `OPEN`).
   - **Circuit Failure Rate (%)**: Radial gauge displaying current sliding window failure percentage with 50% trip alert threshold.
   - **Rate Limit Drops (429 / s)**: Sparkline stat of token depletion rate.
   - **Inflight Active Requests**: Gauge of concurrent netty request handlers currently in flight.

2. **Throughput & Rate Limiting Deep-Dive**:
   - **Inbound Throughput by HTTP Status Code**: Multi-line graph with color-coded codes (Green for 200, Orange for 429, Red for 503, Purple for 401).
   - **Rate Limit & Fault Events (Cumulative Increase)**: Trend view displaying 1-minute delta increases of 429 token rejections and 503 circuit-breaker fallbacks.

3. **Latency Distribution (SLA & Percentiles)**:
   - **Gateway Ingress Latency Percentiles**: P50 (median), P95, and P99 response curves derived from Prometheus histogram buckets.
   - **Edge vs Upstream Mean Latency Comparison**: Breaks down total turnaround time into internal gateway processing overhead versus downstream network latency.

4. **JVM & System Resource Utilization**:
   - **JVM Generational ZGC Heap Memory**: Heap Used vs Committed vs Max limits.
   - **CPU Core Utilization**: Host system CPU vs Gateway process CPU percentage.

---

## 4. Live Verification with k6

To observe the Grafana dashboard under live traffic:

```bash
# 1. Start full observability stack
docker compose up -d

# 2. Open Grafana in your browser
# URL: http://localhost:3000 (admin / admin)
# Navigate to: Dashboards -> Gateway Observability -> API Rate Limiter & Gateway Overview

# 3. Trigger 25-request token exhaustion burst
docker compose --profile load-test run --rm k6 run /scripts/rate-limit-burst.js

# 4. Trigger Circuit Breaker Trip & Recovery cycle
docker compose --profile load-test run --rm k6 run /scripts/circuit-breaker-trip.js
```

During execution:
- The **Rate Limit Drops (429/s)** panel will spike as requests exceed the 10-token burst allowance.
- The **Circuit Breaker State** panel transitions from `CLOSED (Healthy)` → `OPEN (Tripped)` → `HALF_OPEN (Testing)` → `CLOSED (Healthy)`.
- The **Inbound Throughput** graph dynamically updates with color-coded HTTP status distributions.
