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
| `scenario7-recall-eval.js` | Recall@10 金标准评测（chunk 级 answerSpan 判定） | **Recall@10**（2026-07-03 的 78.6%→100% 是 file 级 7 文档小库数据，chunk 级正式数字待补，见文末验证记录） |
| `scenario8-token-usage.js` | 每会话 prompt token 消耗 | **prompt tokens 降低 C%** |
| `scenario9-search-cache-latency.js` | 同一 query 冷/热缓存 p95 对比 | **混合检索 p95 延迟改善（隔离 embedding 缓存这一个变量）** |

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

### 4. Recall@10 评测（78.6% → 100%，2026-07-03 实测）

1. 准备金标准集：编辑 `data/golden-set.json`，把每条 query 的 `relevantFileMd5s` 换成知识库中真实相关文档的 fileMd5（建议 ≥30 条查询，混合语义型与关键词型，否则数据没有说服力）
2. 当前分支跑出 **B%**：

```bash
k6 run scenario7-recall-eval.js
```

3. 切到 parent-child chunking / 混合检索改造前的基线分支，重建索引后用**同一份 golden-set** 再跑，得到 **A%**

结果：`recall_at_10` 的 `avg` 即平均 Recall@10。

### 5. 混合检索缓存 冷/热 p95 对比

```bash
k6 run -e ITERATIONS=15 scenario9-search-cache-latency.js
```

同一条（每轮随机拼后缀保证全新）query 连续打两次：第一次一定 cache miss（等价于 `519226c` 优化前——每次都要现算 embedding），第二次应该命中 Redis 缓存（等价于优化后）。排除了"query 难度不同"这个混淆变量，只测"这条 query 有没有被缓存过"一个变量。

⚠️ 前置条件：`embedding.api.key` 必须能正常调用（真实调用会产生费用）。key 失效时 cold/warm 会测出同样的数字（见文末验证记录），那不代表缓存没用，代表这次测试本身没跑起来。

结果读取：`search_latency_cold` 的 p95 是"未命中缓存"延迟，`search_latency_warm` 的 p95 是"命中缓存"延迟，两者相减/相除即缓存带来的改善幅度。

### 6. Prompt token 消耗（C%）

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

## 验证记录（2026-07-16）

针对"Recall@10 从 79% 到 100%"和"p95 延迟从 1300ms 降到 260ms"两个数据做的复核，结论分开看：

### 先修正一个错误归因：401 不是 key 失效，是 profile 配置

当天最初把重跑失败（recall 32.1%、延迟 3.67s）归因为"`application.yml` 里的 OpenAI key 失效"。**这个归因是错的**：dashboard 显示 key 正常且有余额，直接 curl `/v1/embeddings` 也能通。真实原因是后端用 dev profile 启动，而 `application-dev.yml` 里写的是 `key: ${OPENAI_API_KEY:}`——环境变量占位符、默认值为空，**覆盖掉了** `application.yml` 里那个有效的 key，等于每次请求都带着空 token 出门。修法：启动前 `export OPENAI_API_KEY=...`。教训：embedding 凭证应该在启动时 fail-fast 校验，而不是每个请求静默重试 3 次然后悄悄降级成纯 BM25。

### Recall@10 79% → 100%：原始数据真实，但方法论有已知缺陷，chunk 级重测被语料事故打断

`data/recall-baseline-result.json`（2026-07-03，78.57%，父子分块/混合 RRF 之前）和 `data/recall-current-result.json`（同日，100%，`17308e9` 之后）确是同脚本、同 golden-set 的前后对照真实产出。**但当时全库只有 7 个文档**，file 级判定 + topK=10 在这种体量下几乎必中——`feature/recall-eval-chunk-level` 分支自己就批评这是"虚高"，并给出 chunk 级 answerSpan 方法论 + 200 篇不含答案的干扰语料。本目录的 `scenario7-recall-eval.js` 已换成 chunk 级版本。

**语料污染事故：** chunk 级首次重跑得到 0%，深挖后确认既不是代码回归也不是排序 bug——库里混进了 `amplify_docs.py` 为 QPS 压测生成的 200 篇"synthetic variant"（把 project-report.md 的段落洗牌重组，脚本头部自己写明"只用于 QPS，不用于 recall 评测"）。答案段落被复制了 90+ 份（ES 实测：answerSpan "Redis bitmap" 出现在 94 个文件 / 593 个 chunk 里，其中 92 个是克隆），按 fileMd5 判相关等于"在 90 份原文克隆里找出原件"，数学上必输。处理：克隆全删，改用 `seed_distractors.py`（剔除含 answerSpan 的段落 + 上传前二次校验）灌干扰语料。**铁律：QPS 扩容语料和 recall 评测语料绝不能共存于同一个索引。**

**顺带挖出的摄入链路真 bug（同日已修）：** 重灌语料时暴露三连缺陷——① 消费者无调参（默认一次 poll 500 条、5 分钟处理上限），重型消费（每条秒级~分钟级）必然超时被踢出组、offset 永不提交、消息无限重放（实测同一文件被处理 10 次，embedding 费用按次重复烧）；② `ParseService` 每次投递盲插 MySQL（实测 16,062 行里只有 2,971 行不重复）；③ `VectorizationService` 用随机 UUID 做 ES 文档 id，重复投递=重复写入。修复：`max-poll-records: 1` + `max.poll.interval.ms: 600000`；ParseService 按 fileMd5 先删后插（幂等）；ES id 改 `fileMd5#chunkId`（幂等覆盖）。修复后实测单遍干净处理，每篇 ~1.3s，逐条提交。

**当前状态：** 干净语料重灌进行到 67/200 时被中断，chunk 级 Recall@10 的正式数字待重灌完成后用 `k6 run scenario7-recall-eval.js` 补测。

### p95 延迟 1300ms → 260ms：一半复现，一半修正

`scenario9-search-cache-latency.js` 的对照设计（同一条全新 query 连打两次，第一次必 miss / 第二次必命中 Redis embedding 缓存，隔离 `519226c` 这一个变量）在 key 修好后跑出了干净数据（30 轮、0 错误，`data/scenario9-final-20260716.json`）：

```
search_latency_cold............: avg=1329ms  med=1292ms  p(95)=1906ms  min=843ms
search_latency_warm............: avg=555ms   med=511ms   p(95)=766ms   min=359ms
```

同日另外两轮结果一致（cold 中位数 1129–1302ms；warm 平均 408–536ms）。结论：**"1300ms" 侧完全复现**（cold 中位数 ≈1.3s，一次 embedding API 往返就是混合检索延迟的大头）；**"260ms" 侧复现不出来**——warm 的典型值是中位数 ≈511ms / p95 ≈766ms，260ms 接近观测下限（min 359ms）而不是中心值。可以引用的诚实说法是：**embedding 缓存命中把混合检索延迟降低约 2.4–2.6 倍（中位数 1292ms → 511ms）**。

之前那次"cold/warm 都是 4 秒毫无差异"的失败运行（原因见上：空 key 导致两边测的都是重试耗尽的退避开销）保留在此作为方法论提醒：**依赖挂了的时候，A/B 会静默地测错对象**——先看后端日志确认每条请求都"向量生成成功"，数据才算数。

### 额外发现：token 消耗对比数据也站不住

`conv18-memoryON.log` / `conv18-memoryOFF.log`（scenario8 的正确方法论：同分支靠配置开关 A/B，而非跨 commit 对比——脚本注释里写明跨 commit 对比"发现是伪命题"）显示：memory ON 总 prompt tokens=107,613，比 OFF 的 100,969 **多花 6.6%**，跟"降低 token 消耗"的说法相反。单轮跑噪声大（每轮 ReAct 工具调用次数不同会污染总量），不算实锤，但目前这个数据不支持"降低"的结论，同样不该拿来引用。

## 注意事项

- **成本**：scenario 5/6/8 会真实调用 LLM / Embedding API；scenario 4/7 只打 ES + embedding（query 向量化），也有少量费用
- **数据隔离**：全部只在开发/测试环境跑，禁止对生产执行
- **环境记录**：出正式数据时记录机器规格、ES/JVM 配置、索引文档量，否则数字不可复现
- **JWT 过期**：长时间压测若中途 401，检查 token 有效期配置
