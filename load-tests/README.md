# PaiSmart 负载测试（k6）

用 [k6](https://k6.io/) 对 PaiSmart 各核心链路做压测与评测，产出简历/汇报中需要的量化数据。

## 前置条件

1. 安装 k6：`winget install k6 --source winget`（或 `choco install k6`）
2. 后端及依赖服务（MySQL/ES/Redis/Kafka/MinIO）已启动，默认地址 `http://localhost:8081`
3. 存在测试账号（默认 `admin/admin123`，可用环境变量覆盖）
4. 检索类场景要求知识库已有入库文档，否则结果为空但接口仍返回 200

## 通用环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BASE_URL` | `http://localhost:8081` | HTTP 地址 |
| `WS_URL` | `ws://localhost:8081` | WebSocket 地址 |
| `K6_USERNAME` / `K6_PASSWORD` | `admin` / `admin123` | 测试账号 |

## 场景一览（与简历数据的对应关系）

| 脚本 | 测什么 | 产出哪个数据 |
|---|---|---|
| `scenario1-login.js` | 登录接口冒烟 | — |
| `scenario2-search.js` | 检索接口冒烟（固定 VU） | — |
| `scenario3-websocket.js` | WS 长连接保活 | — |
| `scenario4-search-capacity.js` | 混合检索容量（开环 arrival-rate） | **[Y] QPS、p95 < [Z] ms** |
| `scenario5-upload-chunked.js` | 5MB 分片上传 + 断点续传 + merge 全链路 | **[X GB] 级文件支撑、上传吞吐** |
| `scenario6-chat-streaming.js` | Agent 流式对话（TTFT / 完成时长） | **流式输出与长任务可观测性佐证** |
| `scenario7-recall-eval.js` | Recall@10 金标准评测 | **Recall@10 从 A% 到 B%** |
| `scenario8-token-usage.js` | 每会话 prompt token 消耗 | **prompt tokens 降低 C%** |

## 使用方法

### 1. 检索容量（[Y] QPS、[Z] ms）

```bash
# 第一步：阶梯爬坡，找 p95 开始劣化的 QPS 区间
k6 run -e MAX_RATE=120 -e P95_MS=200 scenario4-search-capacity.js

# 第二步：用略低于劣化点的速率恒定验证 5 分钟
k6 run -e TARGET_QPS=80 -e STEADY_DURATION=5m -e P95_MS=200 scenario4-search-capacity.js
```

thresholds 全绿时：`TARGET_QPS` 即 **[Y]**，输出里 `http_req_duration` 的 `p(95)` 即 **[Z]**。

> 注意：单机 localhost 压测结果偏乐观（无真实网络开销）；正式数据建议从独立压测机打，且 ES 堆内存、JVM 参数与目标环境一致。

### 2. 分片上传（[X GB]）

```bash
# 默认 20MB 文件、2 VU × 2 次
k6 run scenario5-upload-chunked.js

# 加大文件验证 GB 级链路
k6 run -e FILE_SIZE_MB=1024 -e UPLOAD_VUS=1 -e UPLOAD_ITERS=1 -e MAX_DURATION=60m scenario5-upload-chunked.js
```

⚠️ **merge 成功后会触发 Kafka → 解析 → 向量化，真实调用 Embedding API（花钱）并写入 ES。只在测试环境跑，跑完清理测试文档。** 若只想测上传链路不想触发向量化，可临时停掉 Kafka consumer。

结果：`upload_megabytes` 为总上传量，`chunk_throughput_mbps` 为分片吞吐，`merge_duration` 为服务端合并耗时。`FILE_SIZE_MB` 能开到多大且 errors=0，就支撑简历中的 **[X GB]**。

### 3. Agent 流式对话

```bash
k6 run -e CHAT_VUS=3 -e CHAT_ITERS=2 scenario6-chat-streaming.js
```

⚠️ 真实调用 DeepSeek，产生 token 费用，注意模型侧限流。

结果：`chat_ttft`（首 token 延迟）、`chat_completion_time`（整轮耗时）、`chat_chunks_per_response`（流式分片数，>1 证明确实是流式而非一次性返回）。

### 4. Recall@10 评测（A% → B%，chunk 级 / 内容命中）

评测判定的是"检索回来的文本块里是否真的包含答案"（`data/golden-set.json` 每条 query 标注
`answerSpans`），而不是"相关文件是否出现在 top-10"。原因见 `scenario7-recall-eval.js` 顶部注释：
小库 file 级判定会虚高到 100%。

**必须先扩库，否则 top-10 没区分度、数字没意义：**

```bash
# 1) 灌入标注文档（golden-set 引用的真实文件）
python scripts/seed_docs.py

# 2) 灌入 100~300 篇「不含任何 answerSpan」的干扰文档，把库撑到有区分度
python scripts/seed_distractors.py --count 200
```

`seed_distractors.py` 会自动从 `golden-set.json` 收集 answerSpans 并剔除含答案的段落，
保证干扰文档不污染 recall（与 `amplify_docs.py` 的区别）。**等 Kafka 解析+向量化完成**
（检查 ES 文档数）后再评测：

```bash
# 当前分支（父子分块 + 混合检索）跑出 B%
k6 run scenario7-recall-eval.js
```

切到改造前的基线分支，用**同一批文档 + 同一份 golden-set** 再跑一次得到 **A%**。

结果：`recall_at_10` 的 `avg` 即平均 Recall@10；`hit_at_10` 为答案块被检索到的 query 占比。
逐条 `[recall] ... HIT/MISS` 日志可看到具体哪条 query 没召回、命中了哪个 span——某条恒 MISS
先检查该 query 的 answerSpans 是否与入库文本一致（PDF 源注意 fi/fl 连字丢字）。

### 5. Prompt token 消耗（C%）

```bash
k6 run -e CONVERSATIONS=3 -e TURNS=5 scenario8-token-usage.js
```

前提：测试账号独占（usage 差值按用户名统计）。当前分支跑出 tokens_new，切到 memory 改造前基线分支同参数跑出 tokens_old：

```
C% = (tokens_old − tokens_new) / tokens_old × 100
```

teardown 末尾会直接打印每会话 prompt tokens。

## 出报告

```bash
# 导出 JSON 汇总
k6 run --summary-export=results/search-capacity.json scenario4-search-capacity.js

# 时序数据（可导入 Grafana）
k6 run --out json=results/raw.json scenario4-search-capacity.js
```

## 注意事项

- **成本**：scenario 5/6/8 会真实调用 LLM / Embedding API；scenario 4/7 只打 ES + embedding（query 向量化），也有少量费用
- **数据隔离**：全部只在开发/测试环境跑，禁止对生产执行
- **环境记录**：出正式数据时记录机器规格、ES/JVM 配置、索引文档量，否则数字不可复现
- **JWT 过期**：长时间压测若中途 401，检查 token 有效期配置
