import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const loginDuration = new Trend('login_duration', true);

export const options = {
  vus: 50,
  duration: '60s',
  thresholds: {
    http_req_duration: ['p(95)<200'],
    errors: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const USERNAME = __ENV.K6_USERNAME || 'admin';
const PASSWORD = __ENV.K6_PASSWORD || 'admin123';

export default function () {
  const payload = JSON.stringify({
    username: USERNAME,
    password: PASSWORD,
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const res = http.post(`${BASE_URL}/api/v1/users/login`, payload, params);

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has token': (r) => {
      try {
        return JSON.parse(r.body).data?.token !== undefined;
      } catch {
        return false;
      }
    },
  });

  errorRate.add(!ok);
  loginDuration.add(res.timings.duration);

  sleep(1);
}
