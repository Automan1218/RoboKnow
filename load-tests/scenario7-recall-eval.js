import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

/**
 * 场景7：Recall@10 离线评测（CHUNK 级 / 内容命中）—— 对应简历 Bullet 2 的 [Recall@10 从 A% 到 B%]
 *
 * 这不是压测，是检索质量评测（1 VU 串行跑完金标准集）。
 * 对 data/golden-set.json 中每条 query 调 /search/hybrid?topK=10。
 *
 * ── 为什么是 chunk 级而不是 file 级 ──────────────────────────────────────────
 * 旧版判定"相关文件是否出现在 top-10"（file 级）。当全库只有几个文档时，topK=10
 * 几乎必然把相关文件都捞回来 → Recall 虚高到 100%，说明不了检索质量。
 *
 * 新版判定"检索回来的文本块里，是否真的包含该 query 的答案"：
 *   - golden-set 每条 query 标注 answerSpans（答案里必然出现的独特文本，OR 语义）；
 *   - 只在【属于相关文件】的返回结果文本中查找 span（父块 contextText 优先，退回子块
 *     textContent），命中任一 span 即视为"答案块被检索到"→ 该 query recall=100，否则 0；
 *   - answerSpans 用内容匹配而非 chunkId，重新分块后无需重新标注。
 *
 * ── 让数字有意义：必须扩库 ────────────────────────────────────────────────
 * 只有 7 个文档时 top-10 没有区分度。先用 seed_docs.py 灌入标注文档，再用
 * seed_distractors.py 灌入 100~300 篇【不含任何 answerSpan】的干扰文档，
 * 让检索必须从上百个文档里挑出正确的那一块，Recall 才有说服力。
 *
 * ── 得到 A% / B% ─────────────────────────────────────────────────────────
 *   - B%（优化后）：当前分支（父子分块 + 混合检索）直接跑；
 *   - A%（基线）：切到改造前的旧分支，同一份 golden-set + 同一批文档再跑一次。
 *
 * 运行：k6 run scenario7-recall-eval.js
 * 结果：recall_at_10 的 avg 即平均 Recall@10（%）；hit_at_10 为答案被检索到的 query 占比。
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
  const unfilledMd5 = golden.filter((g) =>
    g.relevantFileMd5s.some((m) => m.startsWith('REPLACE_'))
  );
  if (unfilledMd5.length > 0) {
    throw new Error(
      `golden-set.json 还有 ${unfilledMd5.length} 条未标注真实 fileMd5，先完成标注再跑评测`
    );
  }
  const noSpans = golden.filter(
    (g) => !Array.isArray(g.answerSpans) || g.answerSpans.length === 0
  );
  if (noSpans.length > 0) {
    throw new Error(
      `golden-set.json 有 ${noSpans.length} 条缺少 answerSpans，chunk 级评测需要每条标注答案片段`
    );
  }
  return { token: login() };
}

// 把一个返回结果里所有可能承载答案的文本拼起来（父块优先，子块兜底）
function resultText(r) {
  return [r.contextText, r.parentContent, r.textContent]
    .filter((t) => typeof t === 'string' && t.length > 0)
    .join('\n');
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

  // 只看属于相关文件的返回块，防止干扰文档偶然包含 span 造成假命中
  const relevantSet = new Set(item.relevantFileMd5s);
  const relevantText = results
    .filter((r) => relevantSet.has(r.fileMd5))
    .map(resultText)
    .join('\n');

  // 该 query 的答案块是否被检索到：命中任一 answerSpan 即算
  const matchedSpan = item.answerSpans.find((span) => relevantText.includes(span));
  const found = matchedSpan !== undefined;
  const recall = found ? 100 : 0;

  recallAt10.add(recall);
  hitAt10.add(found);

  const fileHit = results.some((r) => relevantSet.has(r.fileMd5));
  console.log(
    `[recall] "${item.query}" -> ${found ? 'HIT' : 'MISS'}` +
      ` (answer chunk ${found ? 'retrieved: "' + matchedSpan + '"' : 'NOT retrieved'};` +
      ` file in top-10: ${fileHit})`
  );
}
