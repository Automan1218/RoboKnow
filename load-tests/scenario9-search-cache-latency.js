import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

/**
 * 场景9：混合检索 Redis embedding 缓存 冷/热 p95 对比
 *
 * 隔离验证 commit 519226c "cache query embeddings in Redis to cut hybrid
 * search latency" 这一个改动的效果，不跨 commit、不需要重建索引：
 *
 *   - cold：每次用一条全新（带随机后缀）query，Redis 里一定没有这个 md5(query)
 *     的缓存 key，一定要走一次真实 embedding API 调用。等价于"缓存优化前"
 *     的行为——因为不管在哪个 commit，一条从没出现过的 query 都必须现算向量。
 *   - warm：cold 请求打完的同一条 query，立刻原样再打一次。此时
 *     Redis 里 EMBEDDING_CACHE_PREFIX+md5(query) 这个 key 刚被 cold 请求
 *     写入（TTL 默认 300s），这次请求应该直接命中缓存，跳过 embedding API 调用。
 *
 * 每轮 cold/warm 用的是同一条 query，只是顺序不同——这样排除了"query 难度不同"
 * 这个混淆变量，测的就是"这条 query 有没有被缓存过"这一个变量的影响。
 *
 * 前置条件：embedding.api.key 必须有效（会触发真实 embedding API 调用，有费用）。
 *
 * 运行：
 *   k6 run scenario9-search-cache-latency.js
 *   k6 run -e ITERATIONS=20 scenario9-search-cache-latency.js
 *
 * 结果读取：
 *   search_latency_cold 的 p95 即"未命中缓存"延迟（约等于优化前）
 *   search_latency_warm 的 p95 即"命中缓存"延迟（优化后的稳态）
 */

const ITERATIONS = parseInt(__ENV.ITERATIONS || '15');

const coldLatency = new Trend('search_latency_cold');
const warmLatency = new Trend('search_latency_warm');

export const options = {
  scenarios: {
    cache_ab: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: ITERATIONS,
      maxDuration: '10m',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

// 固定的语义查询模板，每轮拼一个随机 token 保证 query 文本全新（cold 一定 miss 缓存）
const QUERY_TEMPLATES = [
  '企业知识库系统的混合检索架构是如何设计的',
  '文档分片上传的断点续传机制是怎么实现的',
  'JWT token 的刷新和黑名单机制',
  'Redis 在系统里承担了哪些缓存职责',
  'Kafka 异步处理文档解析的流程',
  'ReAct Agent 的工具调用和迭代终止条件',
  '组织标签体系的权限继承规则',
  '会话记忆的长短期压缩策略',
  'Elasticsearch 索引的字段映射设计',
  '前后端接口鉴权的整体链路',
  '文件去重使用的 MD5 校验流程',
  '向量化模型的维度和降维方案',
  'WebSocket 长连接的心跳与重连',
  '数据库连接池的调优参数',
  '限流和熔断的实现方式',
];

export function setup() {
  return { token: login() };
}

export default function (data) {
  const idx = (__ITER || 0) % QUERY_TEMPLATES.length;
  const uniqueQuery = `${QUERY_TEMPLATES[idx]} [probe-${__VU}-${__ITER}-${Date.now()}]`;

  const coldRes = http.get(
    `${BASE_URL}/api/v1/search/hybrid?query=${encodeURIComponent(uniqueQuery)}&topK=10`,
    authHeaders(data.token)
  );
  const coldOk = check(coldRes, { 'cold: status 200': (r) => r.status === 200 });
  if (coldOk) coldLatency.add(coldRes.timings.duration);

  const warmRes = http.get(
    `${BASE_URL}/api/v1/search/hybrid?query=${encodeURIComponent(uniqueQuery)}&topK=10`,
    authHeaders(data.token)
  );
  const warmOk = check(warmRes, { 'warm: status 200': (r) => r.status === 200 });
  if (warmOk) warmLatency.add(warmRes.timings.duration);

  console.log(
    `[cache-ab] cold=${coldRes.timings.duration.toFixed(0)}ms warm=${warmRes.timings.duration.toFixed(0)}ms query="${uniqueQuery.slice(0, 30)}..."`
  );
}
