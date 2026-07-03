import http from 'k6/http';
import { check, fail } from 'k6';
import crypto from 'k6/crypto';
import { Rate, Trend, Counter } from 'k6/metrics';
import { login, authHeaders } from './lib/auth.js';
import { BASE_URL } from './lib/config.js';

/**
 * 场景5：大文件分片上传全链路 —— 对应简历 Bullet 1
 * （5MB 分片 + MD5 去重 + 断点续传 + MinIO 服务端 merge）
 *
 * 每次迭代完整走一遍：
 *   1. 生成 FILE_SIZE_MB 大小的唯一内容并计算 MD5
 *   2. 按 5MB 分片依次上传 /api/v1/upload/chunk
 *   3. 上传一半时查 /api/v1/upload/status 验证断点续传状态正确（uploaded 列表）
 *   4. 全部分片完成后调 /api/v1/upload/merge 触发 MinIO 合并
 *
 * ⚠️ 注意：merge 成功后会发 Kafka 任务，触发解析 + 向量化（真实调用
 * Embedding API，产生费用并写入 ES）。只在测试环境跑，跑完记得清理文档。
 *
 * 运行：
 *   k6 run scenario5-upload-chunked.js
 *   k6 run -e FILE_SIZE_MB=100 -e UPLOAD_VUS=4 -e UPLOAD_ITERS=5 scenario5-upload-chunked.js
 *
 * 结果读取：
 *   - 吞吐（MB/s）= upload_megabytes（总量）/ 测试时长，或看 chunk_throughput_mbps
 *   - [X GB] 支撑数据 = FILE_SIZE_MB 开大后链路仍稳定（errors=0, merge 成功）
 */

const CHUNK_SIZE = 5 * 1024 * 1024; // 5MB，与前端上传约定一致
const FILE_SIZE_MB = parseInt(__ENV.FILE_SIZE_MB || '20');
const ORG_TAG = __ENV.ORG_TAG || ''; // 不传则后端取用户主组织标签

const errorRate = new Rate('errors');
const chunkDuration = new Trend('chunk_upload_duration', true);
const chunkThroughput = new Trend('chunk_throughput_mbps');
const mergeDuration = new Trend('merge_duration', true);
const uploadedMb = new Counter('upload_megabytes');

export const options = {
  scenarios: {
    upload: {
      executor: 'per-vu-iterations',
      vus: parseInt(__ENV.UPLOAD_VUS || '2'),
      iterations: parseInt(__ENV.UPLOAD_ITERS || '2'),
      maxDuration: __ENV.MAX_DURATION || '15m',
    },
  },
  thresholds: {
    errors: ['rate<0.01'],
    merge_duration: ['p(95)<10000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

// init 阶段构造 1MB 基础块，迭代时拼接复用，避免每次生成大随机串拖慢 VU
const BASE_BLOCK = (() => {
  const line = 'PaiSmart load-test payload 0123456789 abcdefghijklmnopqrstuvwxyz.\n';
  let block = '';
  while (block.length < 1024 * 1024) block += line;
  return block.substring(0, 1024 * 1024);
})();

export function setup() {
  return { token: login() };
}

export default function (data) {
  // 唯一头部保证每个文件 MD5 不同，不会命中秒传/去重逻辑
  const header = `k6-vu${__VU}-iter${__ITER}-${Date.now()}\n`;
  let content = header;
  while (content.length < FILE_SIZE_MB * 1024 * 1024) {
    content += BASE_BLOCK;
  }
  content = content.substring(0, FILE_SIZE_MB * 1024 * 1024);

  const fileMd5 = crypto.md5(content, 'hex');
  const fileName = `k6-upload-${fileMd5.substring(0, 8)}.txt`;
  const totalSize = content.length;
  const totalChunks = Math.ceil(totalSize / CHUNK_SIZE);
  const auth = authHeaders(data.token);

  for (let i = 0; i < totalChunks; i++) {
    const chunk = content.substring(i * CHUNK_SIZE, Math.min((i + 1) * CHUNK_SIZE, totalSize));

    const body = {
      fileMd5: fileMd5,
      chunkIndex: String(i),
      totalSize: String(totalSize),
      fileName: fileName,
      totalChunks: String(totalChunks),
      isPublic: 'false',
      file: http.file(chunk, fileName, 'text/plain'),
    };
    if (ORG_TAG) body.orgTag = ORG_TAG;

    const res = http.post(`${BASE_URL}/api/v1/upload/chunk`, body, auth);

    const ok = check(res, {
      'chunk uploaded': (r) => r.status === 200,
    });
    errorRate.add(!ok);
    if (!ok) {
      fail(`chunk ${i}/${totalChunks} upload failed: status=${res.status} body=${res.body}`);
    }

    chunkDuration.add(res.timings.duration);
    const mb = chunk.length / 1024 / 1024;
    uploadedMb.add(mb);
    chunkThroughput.add(mb / (res.timings.duration / 1000));

    // 上传过半时验证断点续传状态：uploaded 列表应与已传分片数一致
    if (i === Math.floor(totalChunks / 2)) {
      const statusRes = http.get(
        `${BASE_URL}/api/v1/upload/status?file_md5=${fileMd5}`,
        auth
      );
      const statusOk = check(statusRes, {
        'resume status correct': (r) => {
          try {
            return JSON.parse(r.body).data.uploaded.length === i + 1;
          } catch {
            return false;
          }
        },
      });
      errorRate.add(!statusOk);
    }
  }

  // 服务端 merge（MinIO compose + 发 Kafka 处理任务）
  const mergeRes = http.post(
    `${BASE_URL}/api/v1/upload/merge`,
    JSON.stringify({ fileMd5: fileMd5, fileName: fileName }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } }
  );

  const mergeOk = check(mergeRes, {
    'merge success': (r) => r.status === 200,
    'has object_url': (r) => {
      try {
        return !!JSON.parse(r.body).data.object_url;
      } catch {
        return false;
      }
    },
  });
  errorRate.add(!mergeOk);
  mergeDuration.add(mergeRes.timings.duration);
}
