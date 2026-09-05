import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// Custom metrics to track rate limiting accuracy
const successCount = new Counter('successful_requests_200');
const rateLimitedCount = new Counter('rate_limited_requests_429');
const rateLimitRate = new Rate('rate_limit_ratio');

export const options = {
  scenarios: {
    // Stage 1: Burst 25 virtual users simultaneously in a tight window
    burst_test: {
      executor: 'per-vu-iterations',
      vus: 25,
      iterations: 1,
      maxDuration: '10s',
    },
  },
  thresholds: {
    // We expect burst capacity (10) to pass, and the remainder (15) to be rejected with 429
    successful_requests_200: ['count>=10'],
    rate_limited_requests_429: ['count>=10'],
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

  const res = http.get(`${BASE_URL}/echo/get`, params);

  const is200 = res.status === 200;
  const is429 = res.status === 429;

  if (is200) {
    successCount.add(1);
    rateLimitRate.add(0);
  } else if (is429) {
    rateLimitedCount.add(1);
    rateLimitRate.add(1);
  }

  check(res, {
    'status is 200 or 429': (r) => r.status === 200 || r.status === 429,
    'has rate-limit remaining header': (r) => r.headers['X-Ratelimit-Remaining'] !== undefined,
    'has request-id header': (r) => r.headers['X-Request-Id'] !== undefined,
    'has trace-id header': (r) => r.headers['X-Trace-Id'] !== undefined,
  });
}
