import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const fastFailCount = new Counter('fast_fail_503_count');
const timeoutCount = new Counter('timeout_503_count');
const recoveredCount = new Counter('recovered_200_count');
const fastFailDuration = new Trend('fast_fail_duration_ms');

export const options = {
  scenarios: {
    circuit_breaker_lifecycle: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '35s',
    },
  },
  thresholds: {
    fast_fail_503_count: ['count>=3'], // At least 3 calls fast-failed by open circuit breaker
    fast_fail_duration_ms: ['p(95)<100'], // Fast-fails happen in < 100ms (no upstream network wait)
    recovered_200_count: ['count>=1'], // Circuit recovers after wait duration
  },
};

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'my-secure-api-key';

export default function () {
  const params = {
    headers: {
      'X-API-Key': API_KEY,
      'Accept': 'application/json',
    },
  };

  console.log('[Phase 1] Sending 5 slow requests (>4s timeout) to trip the circuit breaker...');
  for (let i = 0; i < 5; i++) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/echo/delay/5`, params);
    const duration = Date.now() - start;

    console.log(`Slow request ${i + 1}: status=${res.status}, duration=${duration}ms`);
    if (res.status === 503 && duration >= 3800) {
      timeoutCount.add(1);
    }
  }

  console.log('[Phase 2] Breaker should now be OPEN. Testing fast-fail fallback...');
  for (let i = 0; i < 4; i++) {
    const start = Date.now();
    // Request a fast endpoint; because breaker is OPEN, it must fast-fail instantly
    const res = http.get(`${BASE_URL}/echo/get`, params);
    const duration = Date.now() - start;

    console.log(`Fast-fail probe ${i + 1}: status=${res.status}, duration=${duration}ms`);
    if (res.status === 503) {
      fastFailCount.add(1);
      fastFailDuration.add(duration);
    }

    check(res, {
      'is 503 fast-fail': (r) => r.status === 503,
      'response time < 150ms': () => duration < 150,
      'has fallback message': (r) => {
        try {
          const body = JSON.parse(r.body);
          return body.error === 'Service Unavailable';
        } catch {
          return false;
        }
      },
    });
    sleep(0.5);
  }

  console.log('[Phase 3] Sleeping 11 seconds for wait-duration-in-open-state (10s) to expire...');
  sleep(11);

  console.log('[Phase 4] Testing recovery in HALF_OPEN / CLOSED state...');
  for (let i = 0; i < 3; i++) {
    const res = http.get(`${BASE_URL}/echo/get`, params);
    console.log(`Recovery probe ${i + 1}: status=${res.status}`);
    if (res.status === 200) {
      recoveredCount.add(1);
    }
    check(res, {
      'recovered to 200 OK': (r) => r.status === 200,
    });
    sleep(0.5);
  }
}
