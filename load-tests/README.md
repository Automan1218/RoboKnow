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
| `scenario7-recall-eval.js` | Recall@10 金标准评测 | **Recall@10 从 78.6% 到 100%**（2026-07-03 实测，见文末验证记录） |
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

### 4. Recall@10 评测（78.6% → 100%，2026-07-03 实测）

1. 准备金标准集：编辑 `data/golden-set.json`，把每条 query 的 `relevantFileMd5s` 换成知识库中真实相关文档的 fileMd5（建议 ≥30 条查询，混合语义型与关键词型，否则数据没有说服力）
2. 当前分支跑出 **B%**：

```bash
k6 run scenario7-recall-eval.js
```

3. 切到 parent-child chunking / 混合检索改造前的基线分支，重建索引后用**同一份 golden-set** 再跑，得到 **A%**

结果：`recall_at_10` 的 `avg` 即平均 Recall@10。

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

## 验证记录（2026-07-16）

针对"Recall@10 从 79% 到 100%"和"p95 延迟从 1300ms 降到 260ms"两个数据做的复核，结论分开看：

### Recall@10 79% → 100%：有真实数据支撑，方法论站得住

`data/recall-baseline-result.json`（2026-07-03，`recall_at_10.avg = 78.57%`，对应旧分支 pre 父子分块/混合 RRF）和 `data/recall-current-result.json`（同日，`avg = 100%`，对应 `17308e9` 之后）是同一份 `golden-set.json`、同一个评测脚本，在改造前后各跑一次的真实产出——数字站得住。

2026-07-16 重跑复现时发现跑不出 100%（只有 32.1%），排查到是 dev 环境 OpenAI embedding API key 返回 401（`application.yml` 里那个 key 失效了），导致每次查询向量化重试 3 次全失败，退化成纯 BM25 关键词匹配——golden-set 里标了"语义型"的 query 全部脱靶。**不是代码回归**，跟当天做的会话历史持久化改动无关（那部分代码完全没碰检索链路）。换一个有效 key 之后应该能复现原始数字，但今天没法验证到底。

### p95 延迟 1300ms → 260ms：目前找不到有效证据

翻了仓库里所有压测产出，最接近这两个数字的是 `baseline-conv-result.log`（p95=1.34s）和 `current-conv-result.log`（p95=246ms），但这两个文件**不能拿来做前后对比**：

- `baseline-conv-result.log` 只有 1 个 HTTP 请求样本（`baseline-token-driver.js` 每次运行只发一次登录请求），n=1 的 p95 没有统计意义
- `current-conv-result.log` 是 4 个不同接口的混合样本（登录 + 建会话 + 2 次查 token usage，来自 `scenario8-token-usage.js`），跟 baseline 测的根本不是同一个接口

也就是说这两个文件本来就不是为了测"混合检索延迟"生成的，是场景 8（token 消耗对比）的副产品。真正测过混合检索延迟的是 project-report.md §19.3 Scenario 2（20 并发 VU，p95=375ms，跟 2,000ms 阈值比），但没有一次可比的"改造前"基线数据。

**如果这个数字要写进简历/报告，需要重新做一次真正对照的实验**——比如同一份代码、同一个 query，Redis embedding 缓存冷启动 vs 命中缓存分别测 p95（正好对应 `519226c` "cache query embeddings in Redis to cut hybrid search latency" 这个 commit 改了什么），但这需要 embedding API key 先恢复可用（见上）。

### 额外发现：token 消耗对比数据也站不住

`conv18-memoryON.log` / `conv18-memoryOFF.log`（scenario8 的正确方法论：同分支靠配置开关 A/B，而非跨 commit 对比——脚本注释里写明跨 commit 对比"发现是伪命题"）显示：memory ON 总 prompt tokens=107,613，比 OFF 的 100,969 **多花 6.6%**，跟"降低 token 消耗"的说法相反。单轮跑噪声大（每轮 ReAct 工具调用次数不同会污染总量），不算实锤，但目前这个数据不支持"降低"的结论，同样不该拿来引用。

## 注意事项

- **成本**：scenario 5/6/8 会真实调用 LLM / Embedding API；scenario 4/7 只打 ES + embedding（query 向量化），也有少量费用
- **数据隔离**：全部只在开发/测试环境跑，禁止对生产执行
- **环境记录**：出正式数据时记录机器规格、ES/JVM 配置、索引文档量，否则数字不可复现
- **JWT 过期**：长时间压测若中途 401，检查 token 有效期配置
