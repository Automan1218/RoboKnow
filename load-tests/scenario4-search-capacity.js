import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

/**
 * 场景4：混合检索容量测试 —— 对应简历 Bullet 2 的 [Y] QPS 和 p95 < [Z] ms
 *
 * 与 scenario2（固定 VU + sleep，闭环模型）不同，本场景使用 arrival-rate
 * （开环模型）直接按目标 QPS 打压力，才能回答"系统能扛多少 QPS"。
 *
 * 两种模式：
 *   1. 阶梯爬坡（默认）：QPS 从低到高逐级爬升，观察 p95 在哪一级开始劣化
 *      k6 run scenario4-search-capacity.js
 *   2. 恒定速率验证：确定候选 QPS 后，用恒定速率跑 5 分钟验证可持续性
 *      k6 run -e TARGET_QPS=80 -e STEADY_DURATION=5m scenario4-search-capacity.js
 *
 * 结果读取：
 *   - [Y] QPS = 恒定速率模式下 thresholds 全绿时的 TARGET_QPS
 *   - [Z] ms  = 该次运行 http_req_duration 的 p(95)
 */

const P95_MS = parseInt(__ENV.P95_MS || '200');
const MAX_RATE = parseInt(__ENV.MAX_RATE || '120');
const TARGET_QPS = __ENV.TARGET_QPS ? parseInt(__ENV.TARGET_QPS) : null;

const errorRate = new Rate('errors');
const searchDuration = new Trend('search_duration', true);

function buildScenario() {
  if (TARGET_QPS) {
    return {
      steady: {
        executor: 'constant-arrival-rate',
        rate: TARGET_QPS,
        timeUnit: '1s',
        duration: __ENV.STEADY_DURATION || '5m',
        preAllocatedVUs: Math.max(20, TARGET_QPS),
        maxVUs: TARGET_QPS * 4,
      },
    };
  }
  return {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: MAX_RATE * 4,
      stages: [
        { duration: '1m', target: Math.ceil(MAX_RATE * 0.25) },
        { duration: '2m', target: Math.ceil(MAX_RATE * 0.5) },
        { duration: '2m', target: Math.ceil(MAX_RATE * 0.75) },
        { duration: '2m', target: MAX_RATE },
        { duration: '1m', target: MAX_RATE },
      ],
    },
  };
}

export const options = {
  scenarios: buildScenario(),
  thresholds: {
    http_req_duration: [`p(95)<${P95_MS}`],
    errors: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 语义查询 + 关键词型查询混合，对应简历中"keyword-heavy queries"场景
const QUERIES = [
  // 语义型
  '人工智能发展趋势',
  '如何提升文档检索的准确率',
  '知识库管理系统的核心功能',
  '机器学习模型训练流程',
  '自然语言处理的应用场景',
  '企业内部文档如何做权限隔离',
  // 关键词型（BM25 优势场景）
  'Elasticsearch kNN 向量检索',
  'JWT token 鉴权',
  'Kafka consumer group rebalance',
  'MinIO 分片上传 merge',
  'text-embedding-v4',
  'Redis 缓存穿透',
  'BM25 评分公式',
  'parent-child chunking',
];

export function setup() {
  return { token: login() };
}

export default function (data) {
  const query = QUERIES[Math.floor(Math.random() * QUERIES.length)];

  const res = http.get(
    `${BASE_URL}/api/v1/search/hybrid?query=${encodeURIComponent(query)}&topK=10`,
    authHeaders(data.token)
  );

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'code 200': (r) => {
      try {
        return JSON.parse(r.body).code === 200;
      } catch {
        return false;
      }
    },
  });

  errorRate.add(!ok);
  searchDuration.add(res.timings.duration);
}
