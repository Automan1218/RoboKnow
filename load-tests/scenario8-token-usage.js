import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { BASE_URL, WS_URL } from './lib/config.js';

/**
 * 场景8：长对话下 prompt token 增长曲线 —— 对应简历 Bullet 4
 *
 * 早期尝试对比"改造前/后两个 git commit"发现是伪命题：8 轮短对话根本
 * 摸不到 memory.context-window=10 这条压缩触发线，测出来 memory 系统
 * 反而更费 token（LTM 注入是固定开销，压缩收益还没触发）。
 *
 * 正确的测法：不跨 commit（会引入 mandatory-search-guard 等无关变量），
 * 而是同一份当前代码，靠 memory.* 配置做开关对照：
 *   - OFF: context-window 设很大 + ltm-top-k=0 → 退化成"裸发全部历史"
 *   - ON : 默认配置（context-window=10, ltm-top-k=3）→ 压缩生效
 * 跑一段长度明显超过 context-window 的对话（这里 18 轮），对比每轮
 * prompt_tokens 的增长曲线，而不是简单求和——求和会被 ReAct 工具调用
 * 次数差异污染，曲线形状（是否封顶）才是压缩机制真正要证明的东西。
 *
 * ⚠️ 前置条件：
 *   1. 测试账号独占（/ai/usage 按用户名统计）
 *   2. 真实调用 LLM，产生费用
 *   3. 两次运行之间需要重启后端并切换 memory.context-window / memory.ltm-top-k
 *
 * 运行：
 *   k6 run -e CONVERSATIONS=1 -e TURNS=18 scenario8-token-usage.js
 */

const TURNS = parseInt(__ENV.TURNS || '5');
const CONVERSATIONS = parseInt(__ENV.CONVERSATIONS || '3');
const TURN_TIMEOUT_MS = parseInt(__ENV.TURN_TIMEOUT_MS || '120000');

const errorRate = new Rate('errors');
const promptTokensPerConv = new Trend('prompt_tokens_per_conversation');
const completionTokensPerConv = new Trend('completion_tokens_per_conversation');

export const options = {
  scenarios: {
    conversations: {
      executor: 'per-vu-iterations',
      vus: 1, // 串行跑，保证 usage 差值干净
      iterations: CONVERSATIONS,
      maxDuration: __ENV.MAX_DURATION || '30m',
    },
  },
  thresholds: {
    errors: ['rate<0.05'],
  },
};

// 固定话术：两次对比运行必须用同一组问题，token 数才有可比性
// 18 轮，明显超过 memory.context-window=10，才能看到压缩是否生效
const SCRIPTED_TURNS = [
  '你好，我叫测试用户，我在做企业知识库系统的性能优化，请记住这个背景',
  '系统的文档上传流程是怎样的？',
  '刚才说的上传流程里，分片大小是多少？',
  '结合我前面说的背景，混合检索应该关注哪些指标？',
  '权限隔离是怎么设计的？',
  '把我们前面聊到的内容总结成三点',
  '针对第一点，再展开讲讲',
  '好的，最后帮我列一个优化 checklist',
  'Kafka 在文件处理流程中起什么作用？',
  '向量化用的是什么 embedding 模型？',
  '结合我最开始说的性能优化背景，Kafka 这块要注意什么？',
  'Elasticsearch 索引设计是怎样的？',
  'JWT token 的刷新机制是怎么设计的？',
  '回到最早我们聊的分片上传，和现在说的 JWT 有没有关联的地方？',
  'Redis 在整个系统里承担哪些角色？',
  '把目前聊到的所有模块列一个清单',
  '如果要做压力测试，你建议先测哪个模块？',
  '最后，结合我最初说的性能优化目标，给一个总结性建议',
];

function fetchUsage(token) {
  const res = http.get(`${BASE_URL}/api/v1/ai/usage`, authHeaders(token));
  const body = JSON.parse(res.body);
  if (body.code !== 200) {
    throw new Error(`fetch usage failed: ${res.body}`);
  }
  return { summary: body.data.summary, records: body.data.records || [] };
}

export function setup() {
  const token = login();
  const before = fetchUsage(token);
  console.log(
    `[before] promptTokens=${before.summary.promptTokens} completionTokens=${before.summary.completionTokens} requests=${before.summary.requestCount}`
  );
  return { token, before };
}

export default function (data) {
  // 每个会话新建独立 session，避免多轮上下文串到同一个会话里
  const createRes = http.post(
    `${BASE_URL}/api/v1/users/conversation/sessions`,
    null,
    authHeaders(data.token)
  );
  let convId = null;
  try {
    convId = JSON.parse(createRes.body).data.convId;
  } catch {
    // fallthrough
  }
  if (!check(createRes, { 'session created': () => !!convId })) {
    errorRate.add(1);
    return;
  }

  const url = `${WS_URL}/chat/${data.token}`;
  let turnIndex = 0;
  let turnsCompleted = 0;

  ws.connect(url, {}, function (socket) {
    const sendNextTurn = () => {
      if (turnIndex >= Math.min(TURNS, SCRIPTED_TURNS.length)) {
        socket.close();
        return;
      }
      socket.send(
        JSON.stringify({ convId: convId, message: SCRIPTED_TURNS[turnIndex] })
      );
      turnIndex++;
    };

    socket.on('open', sendNextTurn);

    socket.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return;
      }
      if (msg.error) {
        errorRate.add(1);
        console.error(`agent error at turn ${turnIndex}: ${msg.error}`);
        socket.close();
        return;
      }
      if (msg.type === 'completion') {
        turnsCompleted++;
        sendNextTurn();
      }
    });

    socket.on('error', (e) => {
      errorRate.add(1);
      console.error(`WS error: ${e.error()}`);
    });

    // 兜底超时：单会话最长 TURNS × 单轮超时
    socket.setTimeout(() => socket.close(), TURN_TIMEOUT_MS * TURNS);
  });

  const allTurnsDone = turnsCompleted === Math.min(TURNS, SCRIPTED_TURNS.length);
  check(null, { 'all turns completed': () => allTurnsDone });
  errorRate.add(!allTurnsDone);
}

export function teardown(data) {
  const after = fetchUsage(data.token);
  const promptDelta = after.summary.promptTokens - data.before.summary.promptTokens;
  const completionDelta = after.summary.completionTokens - data.before.summary.completionTokens;
  const requestDelta = after.summary.requestCount - data.before.summary.requestCount;

  promptTokensPerConv.add(promptDelta / CONVERSATIONS);
  completionTokensPerConv.add(completionDelta / CONVERSATIONS);

  // 按 id 升序排序，取本次运行新增的最后 requestDelta 条，还原调用顺序的增长曲线
  const sorted = after.records.slice().sort((a, b) => a.id - b.id);
  const newRecords = sorted.slice(Math.max(0, sorted.length - requestDelta));

  console.log('──────────────────────────────────────────────');
  console.log(`[token-usage] conversations=${CONVERSATIONS} turns/conv=${TURNS}`);
  console.log(`[token-usage] LLM requests during test: ${requestDelta}`);
  console.log(`[token-usage] total prompt tokens:      ${promptDelta}`);
  console.log(`[token-usage] total completion tokens:  ${completionDelta}`);
  console.log(`[token-usage] prompt tokens / conversation: ${(promptDelta / CONVERSATIONS).toFixed(0)}`);
  console.log('[token-usage] per-call prompt_tokens growth curve (call#: tokens):');
  newRecords.forEach((r, i) => {
    console.log(`[token-usage]   call ${i + 1}: promptTokens=${r.promptTokens} completionTokens=${r.completionTokens} op=${r.operation}`);
  });
  console.log('──────────────────────────────────────────────');
}
