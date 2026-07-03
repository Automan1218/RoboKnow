import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

/**
 * 场景7：Recall@10 离线评测 —— 对应简历 Bullet 2 的 [Recall@10 从 A% 到 B%]
 *
 * 这不是压测，是检索质量评测（1 VU 串行跑完金标准集）。
 * 对 data/golden-set.json 中每条 query 调 /search/hybrid?topK=10，
 * 计算 Recall@10 = |命中的相关文档| / |标注的相关文档|。
 *
 * 前置条件：
 *   1. 先把标注文档上传入库（可用 scenario5），等向量化完成
 *   2. 把每条 query 的 relevantFileMd5s 换成真实 fileMd5
 *
 * 得到 A% / B%：
 *   - B%（优化后）：当前分支直接跑
 *   - A%（基线）：切到 parent-child chunking / 混合检索改造前的旧分支，
 *     同一份 golden-set 再跑一次。两次结果相除即提升幅度。
 *
 * 运行：
 *   k6 run scenario7-recall-eval.js
 *
 * 结果读取：
 *   - recall_at_10 的 avg 即平均 Recall@10（百分比）
 *   - hit_at_10 是"至少命中一个相关文档"的查询占比
 */

const golden = new SharedArray('golden', () =>
  JSON.parse(open('./data/golden-set.json'))
);

const recallAt10 = new Trend('recall_at_10');
const hitAt10 = new Rate('hit_at_10');
const errorRate = new Rate('errors');

export const options = {
  scenarios: {
    eval: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: golden.length,
      maxDuration: '10m',
    },
  },
  thresholds: {
    errors: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max'],
};

export function setup() {
  const unfilled = golden.filter((g) =>
    g.relevantFileMd5s.some((m) => m.startsWith('REPLACE_'))
  );
  if (unfilled.length > 0) {
    throw new Error(
      `golden-set.json 还有 ${unfilled.length} 条未标注真实 fileMd5，先完成标注再跑评测`
    );
  }
  return { token: login() };
}

export default function (data) {
  const item = golden[exec.scenario.iterationInTest];

  const res = http.get(
    `${BASE_URL}/api/v1/search/hybrid?query=${encodeURIComponent(item.query)}&topK=10`,
    authHeaders(data.token)
  );

  let results = [];
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'code 200': (r) => {
      try {
        const body = JSON.parse(r.body);
        results = body.data || [];
        return body.code === 200;
      } catch {
        return false;
      }
    },
  });
  errorRate.add(!ok);
  if (!ok) return;

  const returnedMd5s = new Set(results.map((r) => r.fileMd5));
  const hits = item.relevantFileMd5s.filter((m) => returnedMd5s.has(m)).length;
  const recall = (hits / item.relevantFileMd5s.length) * 100;

  recallAt10.add(recall);
  hitAt10.add(hits > 0);

  console.log(
    `[recall] "${item.query}" -> ${hits}/${item.relevantFileMd5s.length} hit, recall@10=${recall.toFixed(1)}%`
  );
}
