# PaiSmart 会话并发、上下文快照与摘要一致性设计

> 状态：设计待评审  
> 日期：2026-07-16  
> 范围：解决同一会话短时间连续收到多条用户消息时的消息丢失、回答乱序、摘要覆盖与上下文版本混用问题。  
> 关联：延续 `2026-06-10-memory-system-design.md` 和 `2026-07-16-conversation-history-persistence-design.md`。本文件只描述方案，不包含代码。

---

## 1. 结论

本方案保留当前的 `ChatWebSocketHandler → ChatHandler → ReactAgentService → MemoryManager` 主体职责，但改变并发边界：

- **同一 convId 的用户轮次严格串行处理**，不同 convId 仍可并行。
- **摘要任务允许与回答生成并行**，但摘要读取不可变输入，发布时必须做版本 CAS。
- **MySQL 是消息、轮次状态和重试依据的真相源**；Redis 是调度、租约、版本元数据和近期上下文的热缓存。
- 客户端消息先获得 `requestId` 幂等身份和 `turnSeq` 会话内序号，持久化成功后才返回 `accepted`。
- 每次回答使用一次固定的 `ContextSnapshot`，生成过程中不重新拼接摘要或历史。
- 摘要只覆盖已经完成的连续轮次；新的压缩请求只推进目标水位，不启动相互覆盖的并发写任务。
- 不用分布式锁包住 Redis 读改写；短原子状态变更使用 Lua。长时间 LLM 调用使用可续租的 runner lease，并由 fencing token/数据库条件更新阻止过期 worker 提交。

该方案的核心不变量是：

1. `turnSeq` 单调递增且同一会话内唯一。
2. `completedThroughTurn` 只前进，不回退，且表示此前轮次均已进入终态。
3. `summaryThroughTurn` 只前进，不得大于 `completedThroughTurn`。
4. 摘要 vN 的正文不可修改；“当前摘要”只通过 CAS 从 vN 指向 vN+1。
5. 回答只引用其快照记录的摘要版本和原始轮次范围。
6. Redis 丢失或任务重复不会造成消息丢失；系统可从 MySQL 恢复。

---

## 2. 当前实现中的并发缺口

### 2.1 回答链路没有同会话顺序保证

`ChatHandler.processMessage()` 对每条 WebSocket 消息直接调用 `CompletableFuture.runAsync()`。连续三条消息会进入三个并发任务：

- 三个任务可能同时调用 `MemoryManager.loadContext()`，读到同一份旧历史；
- 后发请求可能先完成并先写入历史；
- `WebSocketSession.sendMessage()` 也可能被多个任务并发调用，造成不同回答的事件交错；
- 用户问题直到回答生成结束才由 `MemoryManager.record()` 保存，因此正在处理的用户消息对后续请求不可见。

### 2.2 Redis history 和 pending 都是非原子读改写

`ConversationMemory` 当前把完整 history JSON 放在单个 key 中，通过 get-modify-set 更新。虽然有写锁，但存在以下问题：

- 获取锁只重试约 150ms，失败后仍然无锁写入；
- 锁值固定为 `1`，释放时直接 `DEL`，过期后可能误删其他 worker 新获得的锁；
- 10 秒 TTL 内任务未完成时没有续租或 fencing；
- `pending_compress` 同样使用 get-modify-set，追加可能互相覆盖。

### 2.3 摘要存在“旧任务覆盖新摘要”和“误清新消息”

`ContextCompressor.compressAsync()` 启动时携带 `existingSummary`，完成后无条件覆盖 `stm_summary`。如果两个任务先后启动、反向完成，旧任务会覆盖新结果。

压缩成功后直接删除整个 `pending_compress`。任务读取 pending 后若又有消息进入 pending，旧任务完成时会连新消息一起删除。

此外，当前消息先从 history 移到 pending，再异步生成摘要。在摘要尚未发布期间，这些消息既不在旧摘要中，也不在 recent history 中，存在临时的上下文覆盖空洞。

### 2.4 MySQL 异步持久化不能作为可靠兜底

`MessagePersistenceService.saveAsync()` 通过 `countByConvId()` 计算下一个 seq，并发时可能得到相同结果。当前表也没有 `(conv_id, seq)` 唯一约束。

`memoryExecutor` 队列满时静默丢弃任务，所以消息持久化和压缩任务都可能在无日志、无重试的情况下消失。`@Transactional` 标在 `MemoryManager.record()` 上也不能把 Redis 写和另一个线程中的数据库写组成同一事务。

---

## 3. 一致性边界与处理策略

系统采用两种不同的并发规则：

| 工作类型 | 同一会话内规则 | 原因 |
|---|---|---|
| 用户轮次及回答 | 严格串行 | 后一轮默认依赖前一轮助手回答；同时避免 WebSocket 输出交错 |
| STM 摘要 | 可与回答并行，单会话最多一个有效压缩器 | 摘要较慢，不应阻塞正常回答；CAS 可保证发布安全 |
| 不同会话 | 并行 | 数据、版本和锁均以 convId 隔离 |
| LTM 事实提取 | 异步、幂等 | 不影响对话正确性，但不得挤占可靠消息任务 |

“严格串行”指逻辑处理顺序，不要求阻塞 WebSocket 收消息。三条消息可以立即被接收并持久化，但第二条只在第一条到达终态后生成回答，第三条再随后处理。

如果业务未来需要“打断前一问，立即处理最新问题”，应设计成显式的 cancel/supersede 语义，而不是让同一会话默认并发生成。

---

## 4. 总体架构

```text
WebSocket message
  │
  ▼
ChatWebSocketHandler
  │ 校验 userId/convId/requestId
  ▼
ConversationCommandService（新增，短事务）
  ├─ MySQL：创建 PENDING turn + outbox，分配 turnSeq
  └─ 返回 accepted(requestId, turnSeq)
             │
             ▼
OutboxPublisher ── XADD ──> Redis chat:dispatch Stream（仅作唤醒）
                                      │
                                      ▼
                           ConversationTurnWorker
                           ├─ 获取 conv runner lease + fence
                           ├─ 按 turnSeq 取最早 PENDING turn
                           ├─ MemoryManager.loadContextSnapshot()
                           ├─ ReactAgentService 生成完整回答
                           ├─ DB 条件提交 assistant + COMPLETE
                           ├─ 更新 Redis tail/meta
                           └─ 按 requestId 串行推送结果

ContextCompressor（独立异步池）
  ├─ 读取 base summary + 固定 turn 范围
  ├─ 调用 LLM Map-Reduce
  └─ Lua CAS 发布新 summary 版本并推进覆盖水位
```

Redis Stream 在此处是“唤醒通道”，不是消息真相源。Stream 事件重复或短暂丢失都不影响消息本身；定时 reconciliation 会从 MySQL 找出超时的 `PENDING/PROCESSING` turn 并重新唤醒。

这一取舍避免引入 Kafka/RabbitMQ，同时复用项目已有的 MySQL、Redis 和 Spring 异步基础设施。

---

## 5. 数据模型

### 5.1 轮次序号与消息顺序

新增会话内 `turnSeq`，每个用户请求占一个轮次。推荐逻辑消息序号：

- user 消息：`messageSeq = turnSeq * 2`
- assistant 消息：`messageSeq = turnSeq * 2 + 1`

这样即使第二条用户消息在第一条回答完成前已经入库，展示和上下文的逻辑顺序仍是：

```text
U1, A1, U2, A2, U3, A3
```

不再通过 `COUNT(*)` 推算 seq。`turnSeq` 应在创建 turn 的 MySQL 短事务中从 `conversation_sessions.next_turn_seq` 原子分配；Redis 中只保存镜像水位，不能成为唯一序号来源。

### 5.2 可靠轮次记录

为避免把调度状态硬塞进消息行，建议新增轻量的 `conversation_turns` 表：

| 字段 | 用途 |
|---|---|
| `conv_id + turn_seq` | 会话内唯一顺序 |
| `request_id` | 客户端幂等键；与 convId 组成唯一约束 |
| `user_content` | 用户原文，接收时同步保存 |
| `status` | `PENDING / PROCESSING / COMPLETE / FAILED / CANCELLED` |
| `attempt_token` | 当前处理尝试标识，防止过期 worker 提交 |
| `runner_fence` | 当前处理者 fencing token |
| `assistant_content` | 完整回答，成功提交时写入 |
| `context_summary_version` | 本回答使用的摘要版本 |
| `context_summary_through_turn` | 摘要覆盖水位 |
| `context_tail_through_turn` | 原始上下文读取上界，通常为 `turnSeq - 1` |
| `retry_count / error_code` | 恢复和观测 |
| 时间字段 | received、started、completed 时间 |

`conversation_messages` 继续作为统一历史展示表，但需增加唯一约束 `(conv_id, seq)`。完成 turn 的数据库事务中一次写入 assistant 行并更新 turn 状态；user 行可在接收事务中写入。

### 5.3 Transactional Outbox

接收用户消息的同一个 MySQL 事务完成：

1. 校验会话属于当前用户且未归档；
2. 按 `(conv_id, request_id)` 查重；
3. 原子分配 `turnSeq`；
4. 插入 user message 和 `PENDING` turn；
5. 插入 `TURN_READY` outbox。

事务提交后才返回 `accepted`。Outbox publisher 把事件写入 Redis Stream，成功后标记已发布。发布重复由 requestId 和 turn 状态消化。

因此：

- DB 不可用：请求不能被接受，客户端可安全重试；
- Redis 不可用：消息已保存，回答延迟但不丢失；
- 服务在 DB commit 后、Redis publish 前崩溃：outbox 恢复后继续发布。

---

## 6. Redis 数据设计

同一会话 key 使用 Redis Cluster hash tag `{convId}`，保证未来启用 Cluster 后，会话内 Lua 操作仍在同一 slot。

### 6.1 会话元数据

`conversation:{convId}:v2:meta`，Hash：

| 字段 | 含义 |
|---|---|
| `completedThroughTurn` | 已连续完成/进入终态的最大 turnSeq 镜像 |
| `summaryVersion` | 当前摘要版本，单调递增 |
| `summaryThroughTurn` | 当前摘要覆盖到的完整轮次 |
| `desiredSummaryThroughTurn` | 压缩器应追赶到的目标水位，只能取 max |
| `compressDirty` | 有未处理压缩目标时为 1 |
| `runnerFence` | 每次 runner lease 所属世代，单调递增 |
| `compressFence` | 每次 compressor lease 所属世代 |
| `sessionEpoch` | 会话归档/重建时递增，阻止旧任务回写 |

元数据是热状态镜像。缺失时从 MySQL 的 turn、summary 记录重建，不把 key miss 当作“空会话”。

### 6.2 不可变摘要

```text
conversation:{convId}:v2:summary:{version}
```

值包含：

```text
version
parentVersion
content
coversFromTurn
coversThroughTurn
sourceDigest
createdAt
```

摘要 key 写入后不可修改。`meta.summaryVersion` 是当前指针。保留最近若干版本用于正在生成回答的快照；不能在有活跃快照时立即删除旧版本。TTL 至少与会话热缓存一致，每次会话活动统一续期。

摘要建议同时持久化到 MySQL，或至少保证可从原始消息重新生成。Redis 版本仍是在线读取和 CAS 的依据。

### 6.3 近期完整轮次缓存

```text
conversation:{convId}:v2:tail
```

使用按 `turnSeq` 排序的 ZSet，member 为包含 turnSeq 的完整 user/assistant 轮次 JSON，score 为 turnSeq。

只缓存 `summaryThroughTurn` 之后的完整轮次。**摘要发布成功前不能先删除候选原文**；发布 CAS 成功后，才可原子清理 `<= newSummaryThroughTurn` 的 tail。

Redis tail miss 或范围不完整时从 `conversation_messages` 回源并回填。

### 6.4 调度 Stream

```text
chat:v2:dispatch
```

字段只需 `convId`、`requestId`、`turnSeq` 和事件类型。使用 consumer group 消费。

它只负责降低轮询延迟：

- 重复事件安全；
- worker 获锁失败时可以 ack，因为持锁 worker 会 drain 该 convId 的所有待处理 turn；
- reconciliation 定时扫描数据库中的待处理/超时 turn，补发唤醒；
- Stream 不保存完整消息正文，避免把 Redis 当唯一可靠存储。

### 6.5 租约 key

```text
conversation:{convId}:v2:runner-lease
conversation:{convId}:v2:compress-lease
```

值为随机 owner token、fence 和 sessionEpoch。runner 与 compressor 使用独立租约，因此压缩不会阻塞回答。

---

## 7. Redis 原子操作边界

短状态变更使用 Lua；不使用“拿锁后 get-modify-set 整段 JSON”。建议定义以下原子操作语义。

### 7.1 获取 runner lease

一次 Lua 操作：

1. 如果 lease 不存在，`HINCRBY meta runnerFence 1`；
2. `SET runner-lease ownerToken:fence:sessionEpoch NX PX leaseTtl`；
3. 返回 fence；
4. 如果 lease 已存在，返回失败，不执行任何业务写入。

续租和释放都必须比较完整 owner token/fence/sessionEpoch 后才 `PEXPIRE` 或 `DEL`。禁止无条件删除锁。

LLM 调用期间 runner 每隔 `leaseTtl / 3` 心跳续租。即使续租失败，旧 worker 也可能继续计算，但它不能通过数据库条件更新和 Redis fence 校验提交结果。

### 7.2 turn 完成后的缓存推进

数据库完成事务成功后，通过 Lua：

1. 校验 `sessionEpoch` 和 runner fence；
2. 将完整 turn `ZADD` 到 tail；
3. 仅按连续性推进 `completedThroughTurn`；
4. 根据 token 预算更新 `desiredSummaryThroughTurn = max(old, target)`；
5. 需要压缩时设置 `compressDirty = 1`；
6. 刷新相关缓存 TTL。

如果 Redis 更新失败，不回滚已经提交的数据库回答；reconciliation 从 DB 重建热状态。

### 7.3 摘要发布 CAS

压缩任务启动时固定以下输入：

```text
baseVersion
baseThroughTurn
targetThroughTurn
sessionEpoch
sourceDigest
```

LLM 完成后 Lua 只在以下条件全部满足时发布：

- `meta.summaryVersion == baseVersion`；
- `meta.summaryThroughTurn == baseThroughTurn`；
- `meta.sessionEpoch == job.sessionEpoch`；
- 当前 compressor fence 仍属于该任务；
- `targetThroughTurn <= completedThroughTurn`。

成功时一次完成：

1. `SET summary:{baseVersion+1}`；
2. 更新 `summaryVersion` 和 `summaryThroughTurn`；
3. `ZREMRANGEBYSCORE tail -inf targetThroughTurn`；
4. 如果 `desiredSummaryThroughTurn > targetThroughTurn`，保留 `compressDirty=1`；否则置 0。

CAS 失败时结果不得覆盖当前摘要。任务读取最新版本后重新计算，或直接结束让 dirty 任务重新调度。

### 7.4 Redis 事务的边界

Lua/MULTI 只能保护 Redis 内部的短操作，不能覆盖：

- LLM 调用；
- 工具调用；
- MySQL 事务；
- WebSocket 推送。

因此不设计“跨 Redis + MySQL 的大事务”。跨存储一致性分别由 transactional outbox、幂等键、条件更新和 reconciliation 达成。

---

## 8. 消息处理时序

### 8.1 接收阶段

客户端发送：

```json
{
  "type": "chat",
  "convId": "...",
  "requestId": "客户端生成的 UUID",
  "message": "..."
}
```

服务端返回：

```json
{
  "type": "accepted",
  "convId": "...",
  "requestId": "...",
  "turnSeq": 12,
  "status": "queued"
}
```

相同 requestId 重试时返回原 turnSeq 和当前状态，不重复创建轮次。旧客户端不传 requestId 时可由服务端生成，但只能保证单次连接内处理，无法对网络重试提供端到端幂等；前端应同步升级。

### 8.2 worker 串行处理

worker 收到任意 convId 唤醒后：

1. 尝试获取该会话 runner lease；失败则退出，不无锁处理。
2. 从 MySQL 读取最小 `PENDING` turnSeq。
3. 用条件更新把它从 `PENDING` 改为 `PROCESSING`，写入 attempt token 和 fence。
4. 获取固定 `ContextSnapshot`。
5. 调用 `ReactAgentService`，在内存中生成完整 finalAnswer。
6. 数据库短事务使用 `WHERE status=PROCESSING AND attempt_token=? AND runner_fence=?` 提交 assistant 和 `COMPLETE`。
7. 提交成功后更新 Redis tail/meta，再向 WebSocket 推送带 requestId/turnSeq 的回答与 completion。
8. 继续处理该 convId 的下一条 PENDING turn；队列排空后释放 lease。

**先提交、后推送**。如果提交后 WebSocket 断线，客户端重连可按 requestId/turnSeq 查询结果；如果先推送后崩溃，则用户可能看见一个数据库不存在的回答。

回答分块事件必须都带 `requestId` 和 `turnSeq`。同一 WebSocket 即使承载不同 convId，也应通过单独的 outbound sender 串行调用 `sendMessage()`。

### 8.3 失败轮次

LLM/工具临时失败按固定次数重试。超过上限后把 turn 标为 `FAILED` 并记录可展示错误；该轮进入终态后允许后续 turn 继续。

上下文构建默认包含失败轮次的 user 原文，但不伪造 assistant 回答。若用户取消，标记 `CANCELLED`，同样不能永久堵塞会话队列。

stop 命令应携带 requestId；只停止指定的 `PROCESSING` turn。仅按 WebSocket sessionId 停止在多条排队消息下语义不明确。

---

## 9. 一致的上下文快照

`MemoryManager.loadContext()` 演进为逻辑上的 `loadContextSnapshot()`，返回：

```text
summaryVersion
summaryThroughTurn
tailFromTurn
tailThroughTurn
completedThroughTurn
LTM facts snapshot
最终 messages
```

构建步骤：

1. 读取 meta，得到 `(summaryVersion, summaryThroughTurn)`。
2. 读取不可变的 `summary:{summaryVersion}`。
3. 从 tail 读取 `(summaryThroughTurn, currentTurnSeq)` 的完整终态轮次；缺失则从 MySQL 回源。
4. 再读一次 meta：若摘要版本或覆盖水位发生变化，重新构建；版本单调递增，不存在 ABA。
5. 固定快照并记录到 `conversation_turns`，之后整个 ReAct/grounding 过程复用，不再读取“最新摘要”。

上下文覆盖区间必须连续：

```text
[摘要覆盖 1..summaryThroughTurn]
+
[原始完整轮次 summaryThroughTurn+1..currentTurnSeq-1]
+
[当前 user message]
```

压缩触发采用低/高水位：

- 低水位（建议沿用 80%）：异步触发压缩；
- 高水位（例如 95%）：如果旧摘要 + 未摘要连续原文已无法安全装入模型窗口，暂停下一轮回答，优先等待当前压缩完成或执行确定性的本地降级压缩。

不能简单丢弃尚未被摘要覆盖的中间轮次，否则虽然 token 数满足要求，语义上仍存在上下文空洞。

---

## 10. 摘要任务设计

### 10.1 只压缩完整连续轮次

每次 turn 完成后计算：

```text
targetThroughTurn = completedThroughTurn - keepRecentTurns
```

只有 `targetThroughTurn > summaryThroughTurn` 才更新 desired 水位。保留最近若干完整轮次原文，避免刚发生的细节过早被摘要损耗。

用户消息刚入队、回答尚未完成时不进入摘要范围。第三条消息的到达本身只改变队列，不会把半个轮次交给压缩器。

### 10.2 single-flight + dirty 水位

同一会话最多一个有效压缩任务：

- 新触发不再直接创建第二个并发 Map-Reduce；
- 只把 `desiredSummaryThroughTurn` 推到更大值并设置 dirty；
- 当前任务成功后若仍落后于 desired，继续下一段；
- 当前任务失败或 worker 崩溃，lease 到期后由 reconciliation 重试。

### 10.3 固定输入和来源校验

压缩输入由旧摘要 vN 和 `(baseThroughTurn, targetThroughTurn]` 的原始完整轮次组成。原始轮次从 MySQL 读取，以 `sourceDigest` 校验输入范围。

新 turn 在压缩期间完成不会使当前任务失效，因为它不在固定 target 范围内；它只会提高 desired 水位。只有 base summary 已被其他任务推进时，当前任务的 CAS 才失败。

---

## 11. 三条消息场景的实际顺序

假设当前摘要为 v7，覆盖到 turn 10：

| 时间 | 事件 | 状态与结果 |
|---|---|---|
| T1 | M1 已完成，触发 J1 | J1 固定输入 `base=v7/through=10/target=11`，异步运行 |
| T2 | M2 到达 | 同步保存为 turn 12，进入 PENDING |
| T3 | worker 处理 M2 | 固定上下文快照为 v7 + 原始 turn 11；即使 J1 稍后完成也不改变本次回答 |
| T4 | M3 到达 | 保存为 turn 13，排在 M2 后，不并发生成 |
| T5 | J1 发布 v8 | CAS 成功，v8 覆盖到 turn 11；v7 暂时保留供 M2 快照使用 |
| T6 | A2 提交 | turn 12 COMPLETE；根据预算提高 desired 压缩水位，已有压缩器则只置 dirty |
| T7 | worker 处理 M3 | 读取此刻最新的一致快照，必然包含 A2；不会与 M2 共用一个可变 history |
| T8 | 后续 J2 发布 | 只能从当前 v8 继续合并；旧 base 的任务 CAS 失败，不能覆盖 |

如果 J1 在 T5 前已有另一个摘要任务发布，J1 CAS 失败并丢弃结果。它绝不能执行“保存摘要 + 清空全部 pending”。

这个场景允许 M2 使用旧摘要，因为旧摘要加连续原始尾部仍是完整上下文；禁止的是在一次回答中同时使用 v7 的正文和 v8 的覆盖水位。

---

## 12. 失败与恢复

| 故障 | 处理结果 |
|---|---|
| WebSocket 重发同一消息 | `(convId, requestId)` 去重，返回原 turn |
| Redis 在接收后不可用 | DB turn/outbox 已提交，恢复后重新唤醒，不丢消息 |
| worker 在 LLM 中崩溃 | lease 到期；超时 PROCESSING turn 被重置/重新 claim |
| 旧 worker 恢复并提交 | attempt token、runner fence、sessionEpoch 条件不匹配，提交失败 |
| 回答 DB 提交成功但 Redis 更新失败 | 回答仍可靠；reconciliation 从 DB 回填 tail/meta |
| 回答提交成功但 WebSocket 推送失败 | 客户端按 requestId 查询/重连补拉 |
| 压缩线程池满 | dirty/desired 状态仍存在，稍后重试；禁止静默丢任务 |
| 压缩任务失败 | 不改变 summary 指针，不删原始 tail，保留 dirty |
| 两个摘要反向完成 | 只有 baseVersion 匹配者 CAS 成功；另一个重算或退出 |
| Redis 所有会话缓存过期 | 从 MySQL message/turn/summary 重建 |
| 会话被归档时仍有旧任务 | sessionEpoch/状态校验阻止提交；worker 停止后清理 v2 keys |

对当前只读搜索工具，回答重试的外部副作用较小。如果未来加入写操作工具，每次工具调用也必须带 `(requestId, stepNo)` 幂等键，否则“回答可重试”仍可能重复执行外部动作。

---

## 13. 对现有组件的设计影响

| 当前组件 | 方案中的职责变化 |
|---|---|
| `ChatWebSocketHandler` | 解析/补充 requestId，调用可靠接收服务，立即发送 accepted；不直接启动回答线程 |
| `ChatHandler` | 从 `CompletableFuture.runAsync` 执行器改为入队门面；stop 按 requestId 定位 |
| `ReactAgentService` | 对一个已 claim 的 turn 和固定 ContextSnapshot 生成完整答案；不自行决定并发和持久化顺序 |
| `MemoryManager` | 提供一致快照、turn 完成后的记忆推进；不再把“问答完成后一次 append”当作消息接收 |
| `ConversationMemory` | 从整段 JSON get-modify-set 改为 versioned meta + immutable summary + ordered tail；所有推进使用 Lua/CAS |
| `ContextCompressor` | 接收固定 CompressionJob；single-flight；CAS 发布；不再清空 pending key |
| `MessagePersistenceService` | 可靠写入进入同步短事务/turn service；不再使用 `countByConvId` 和 best-effort fire-and-forget |
| `AsyncConfig` | 可靠调度、压缩、LTM 分池；关键任务拒绝时留在 DB/dirty 状态，不能静默丢弃 |
| `ConversationSession` | 增加 nextTurnSeq/可选 sessionEpoch；轮次计数按成功终态推进 |

建议新增的职责组件：

- `ConversationCommandService`：接收、幂等、分配 turnSeq、写 outbox；
- `OutboxPublisher`：可靠发布 Redis 唤醒事件；
- `ConversationTurnWorker`：按 convId 获取租约并顺序 drain；
- `ConversationReconciler`：恢复超时 turn、补发唤醒、修复 Redis 热状态；
- `ContextSnapshot` / `CompressionJob`：显式承载版本和水位，避免用裸字符串传递旧摘要。

---

## 14. 分阶段落地

### 阶段一：先关闭消息乱序和丢失窗口

1. 引入 requestId、turnSeq、`conversation_turns` 和 outbox。
2. 给 `conversation_messages` 增加 `(conv_id, seq)` 唯一约束；上线前审计并处理已有重复 seq。
3. 把用户消息接收改为同步 MySQL 短事务。
4. 引入 per-conversation runner lease 和顺序 worker。
5. 回答改为先 DB commit、后 WebSocket 推送。

完成此阶段后，即使暂时沿用旧摘要，消息不丢和回答顺序问题已解决。

### 阶段二：摘要版本化

1. 上线 v2 meta、immutable summary 和 tail key，避免与旧 key 混写。
2. ContextSnapshot 双读校验，缓存 miss 回源 MySQL。
3. 改造 compressor 为固定范围、single-flight、dirty 水位和 Lua CAS。
4. 删除“先移出 history、成功后清空 pending”的流程。

### 阶段三：恢复与运维闭环

1. 上线 reconciliation、超时 PROCESSING 恢复和 outbox 监控。
2. 分离关键调度、摘要和 LTM executor。
3. 增加积压、版本冲突、租约丢失、上下文回源等指标。
4. 稳定后下线 legacy history/summary/pending/write_lock key。

滚动发布期间使用 `:v2:` key 命名空间和功能开关，避免新旧实例共同写同一状态。旧摘要没有 `coversThroughTurn`，不能直接假定覆盖范围；迁移时应基于 MySQL 原始消息重建 v1 摘要，或在重建完成前走原始消息降级路径。

---

## 15. 测试与验收标准

### 15.1 并发功能测试

- 同一 convId 并发发送 3、10、100 条消息，turnSeq 唯一且响应严格按 turnSeq 完成。
- 不同 convId 可同时生成，不被全局锁串行化。
- 同一 requestId 并发重试只产生一个 turn 和一份 assistant 回答。
- WebSocket 所有 chunk/completion 均可按 requestId 归属，不交错成错误文本。

### 15.2 摘要竞态测试

- J1 读取 v7 后暂停，J2 尝试发布 v8，再恢复 J1；验证旧任务不能覆盖当前摘要。
- J1 运行期间继续完成多个 turn；验证 J1 只推进自己的 target，新的 desired 水位仍保留。
- 摘要失败/超时后原始 tail 未被删除，上下文不存在覆盖空洞。
- context 在摘要 CAS 前后并发读取，只能得到完整的 v7 快照或完整的 v8 快照。

### 15.3 故障注入测试

- DB commit 后、outbox publish 前杀进程，恢复后消息被处理一次。
- LLM 调用中杀 worker，lease 到期后重试，最终只提交一份回答。
- 旧 worker lease 过期后恢复，fence 校验拒绝其提交。
- Redis 清库后，从 MySQL 恢复会话上下文和待处理 turn。
- executor 队列打满，关键 turn 和 compressDirty 不消失。

### 15.4 必须监控的指标

- `conversation_turn_pending_age_seconds`；
- `conversation_turn_retry_total` / `failed_total`；
- `conversation_runner_lease_lost_total`；
- `summary_version_cas_conflict_total`；
- `summary_lag_turns = desiredSummaryThroughTurn - summaryThroughTurn`；
- `context_snapshot_retry_total` / `context_db_fallback_total`；
- outbox 未发布数量和最老记录年龄；
- Redis dispatch consumer lag。

验收底线：任何已返回 `accepted` 的消息最终必须处于 `COMPLETE/FAILED/CANCELLED` 之一，不能无期限消失；任何回答都能追溯到 requestId、turnSeq 和固定上下文版本。

---

## 16. 明确不采用的做法

- 不继续使用“获取写锁失败后仍写入”。
- 不使用固定值锁和无条件 `DEL` 解锁。
- 不持有数据库事务或 Redis 原子锁等待 LLM。
- 不把整个会话历史作为单个 JSON 做 get-modify-set。
- 不通过 `COUNT(*)` 分配消息序号。
- 不让多个压缩任务无条件覆盖同一个 `stm_summary`。
- 不在摘要发布前删除原始候选消息，也不清空整个 pending 缓冲。
- 不把关键持久化交给会静默拒绝任务的 best-effort executor。
- 不依赖 Redis Stream 本身实现唯一一次处理；采用至少一次唤醒 + 数据库幂等提交。

---

## 17. 方案取舍

本方案比单纯给 `ConversationMemory` 加一把更长的锁多了 turn/outbox/worker，但这是为了覆盖当前锁无法解决的三个跨时段问题：LLM 调用很长、数据库与 Redis 不在同一事务、摘要结果会延迟返回。

没有引入新的消息中间件，仍使用项目现有技术栈。代价是增加两类数据库记录和 reconciliation；收益是消息可恢复、同会话顺序明确、摘要可验证发布，并且每份回答都有可审计的上下文版本。
