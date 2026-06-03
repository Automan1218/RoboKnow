import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const searchDuration = new Trend('search_duration', true);

export const options = {
  vus: 20,
  duration: '120s',
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    errors: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

const QUERIES = [
  '人工智能发展趋势',
  'RAG检索增强生成',
  '知识库管理系统',
  '机器学习算法',
  '自然语言处理',
];

// 登录获取 token（setup 阶段执行一次）
export function setup() {
  const res = http.post(
    `${BASE_URL}/api/v1/users/login`,
    JSON.stringify({ username: 'admin', password: 'admin123' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  const token = JSON.parse(res.body).data?.token;
  if (!token) throw new Error('Login failed in setup: ' + res.body);
  return { token };
}

export default function (data) {
  const query = QUERIES[Math.floor(Math.random() * QUERIES.length)];

  const params = {
    headers: {
      'Authorization': `Bearer ${data.token}`,
    },
  };

  const res = http.get(
    `${BASE_URL}/api/v1/search/hybrid?query=${encodeURIComponent(query)}&topK=10`,
    params
  );

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has data': (r) => {
      try {
        return JSON.parse(r.body).code === 200;
      } catch {
        return false;
      }
    },
  });

  errorRate.add(!ok);
  searchDuration.add(res.timings.duration);

  sleep(1);
}
