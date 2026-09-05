import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const successCount = new Counter('steady_success_200');
const rateLimitedCount = new Counter('steady_rate_limited_429');
const errorRate = new Rate('steady_error_rate');

export const options = {
  scenarios: {
    // Constant arrival rate of 4 requests per second (below replenishRate of 5 tokens/s)
    // for 15 seconds. Should result in 0% 429 errors.
    steady_stream: {
      executor: 'constant-arrival-rate',
      rate: 4,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },
  thresholds: {
    steady_error_rate: ['rate==0'], // Zero 429 errors expected
    http_req_duration: ['p(95)<1500'], // 95% of requests under 1500ms
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
    errorRate.add(0);
  } else {
    if (is429) {
      rateLimitedCount.add(1);
    }
    errorRate.add(1);
  }

  check(res, {
    'status is 200 OK': (r) => r.status === 200,
    'rate limit remaining >= 0': (r) => parseInt(r.headers['X-Ratelimit-Remaining'] || '-1') >= 0,
    'has trace-id': (r) => r.headers['X-Trace-Id'] !== undefined,
  });
}
