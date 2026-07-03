import ws from 'k6/ws';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { login } from './lib/auth.js';
import { WS_URL } from './lib/config.js';

/**
 * 场景6：ReAct Agent WebSocket 流式对话 —— 对应简历 Bullet 3
 * （token 流式输出、长任务可观测、多步推理链路）
 *
 * 与 scenario3（长连接保活）不同，本场景测的是单轮问答的流式体验指标：
 *   - chat_ttft：发送问题 → 收到第一个 {"chunk"} 的耗时（首 token 延迟）
 *   - chat_completion_time：发送问题 → 收到 {"type":"completion"} 的总耗时
 *   - chat_chunks_per_response：每轮回复的流式分片数
 *
 * ⚠️ 每次迭代真实调用 DeepSeek + Embedding API，产生 token 费用。
 * VU 数别开大（默认 3），迭代数默认 2，且要遵守模型侧限流。
 *
 * 运行：
 *   k6 run scenario6-chat-streaming.js
 *   k6 run -e CHAT_VUS=5 -e CHAT_ITERS=3 scenario6-chat-streaming.js
 */

const CHAT_TIMEOUT_MS = parseInt(__ENV.CHAT_TIMEOUT_MS || '120000');

const errorRate = new Rate('errors');
const ttft = new Trend('chat_ttft', true);
const completionTime = new Trend('chat_completion_time', true);
const chunksPerResponse = new Trend('chat_chunks_per_response');

export const options = {
  scenarios: {
    chat: {
      executor: 'per-vu-iterations',
      vus: parseInt(__ENV.CHAT_VUS || '3'),
      iterations: parseInt(__ENV.CHAT_ITERS || '2'),
      maxDuration: __ENV.MAX_DURATION || '15m',
    },
  },
  thresholds: {
    errors: ['rate<0.05'],
    chat_ttft: [`p(95)<${__ENV.TTFT_P95_MS || '5000'}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

const QUESTIONS = [
  '请简要介绍知识库中关于文档上传流程的内容',
  '系统的混合检索是怎么实现的？',
  'JWT 鉴权失败一般有哪些原因？',
  '请总结知识库里关于权限隔离的设计',
  'Kafka 在文件处理流程中起什么作用？',
  '向量化流程用的是什么 embedding 模型？',
];

export function setup() {
  return { token: login() };
}

export default function (data) {
  const question = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  const url = `${WS_URL}/chat/${data.token}`;

  let sentAt = 0;
  let gotFirstChunk = false;
  let chunkCount = 0;
  let completed = false;

  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', () => {
      sentAt = Date.now();
      socket.send(JSON.stringify({ message: question }));
    });

    socket.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return; // 非 JSON 消息忽略
      }

      if (msg.error) {
        errorRate.add(1);
        console.error(`agent error: ${msg.error}`);
        socket.close();
        return;
      }

      if (msg.chunk !== undefined) {
        chunkCount++;
        if (!gotFirstChunk) {
          gotFirstChunk = true;
          ttft.add(Date.now() - sentAt);
        }
        return;
      }

      if (msg.type === 'completion') {
        completed = true;
        completionTime.add(Date.now() - sentAt);
        chunksPerResponse.add(chunkCount);
        socket.close();
      }
    });

    socket.on('error', (e) => {
      errorRate.add(1);
      console.error(`WS error: ${e.error()}`);
    });

    // 超时保护：LLM 卡住时不无限等
    socket.setTimeout(() => {
      if (!completed) {
        console.warn(`no completion within ${CHAT_TIMEOUT_MS}ms, question="${question}"`);
      }
      socket.close();
    }, CHAT_TIMEOUT_MS);
  });

  const connOk = check(res, {
    'ws connected': (r) => r && r.status === 101,
  });
  check(null, {
    'response completed': () => completed,
    'streamed as chunks': () => chunkCount > 1,
  });

  errorRate.add(!connOk);
  errorRate.add(!completed);
}
