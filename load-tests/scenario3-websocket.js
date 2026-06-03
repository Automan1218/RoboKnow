import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Rate, Counter } from 'k6/metrics';

const errorRate = new Rate('errors');
const wsDisconnects = new Counter('ws_disconnects');
const wsResponses = new Counter('ws_responses');

export const options = {
  vus: 10,
  duration: '5m',
  thresholds: {
    errors: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8081';

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
  const url = `${WS_URL}/chat/${data.token}`;

  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', () => {
      // 每 30 秒发一条消息
      socket.setInterval(() => {
        socket.send('请简要介绍一下人工智能的发展历史');
      }, 30000);

      // 发第一条消息
      socket.send('你好，请介绍一下本知识库的主要内容');
    });

    socket.on('message', (msg) => {
      wsResponses.add(1);
      check(msg, {
        'response not empty': (m) => m && m.length > 0,
      });
    });

    socket.on('close', () => {
      wsDisconnects.add(1);
    });

    socket.on('error', (e) => {
      errorRate.add(1);
      console.error('WS error:', e);
    });

    // 持续 5 分钟后断开
    socket.setTimeout(() => {
      socket.close();
    }, 295000);
  });

  check(res, {
    'ws connected': (r) => r && r.status === 101,
  });
}
