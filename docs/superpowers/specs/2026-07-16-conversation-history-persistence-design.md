# PaiSmart 会话历史持久化设计（Redis 热缓存 + MySQL 兜底）

> 状态：设计待评审
> 日期：2026-07-16
> 范围：解决聊天消息只存 Redis、7 天 TTL 过期即永久丢失的问题。Redis 转为热缓存，MySQL 作为真相源。
> 关联：延续 `2026-06-10-memory-system-design.md` 的记忆系统重构，本次只动"消息落库"这一环，不改 LTM 事实抽取 / STM 压缩逻辑。

---

## 1. 背景与问题

### 1.1 现状

`ConversationMemory`（`src/main/java/com/yizhaoqi/roboknow/memory/ConversationMemory.java`）把完整消息历史存在 Redis：

- Key：`conversation:{convId}`
- TTL：7 天（`CONV_TTL`），每次写入刷新
- 无任何 MySQL 侧的原始消息持久化

`conversation_sessions` 表只存会话元信息（标题、创建时间、round 数），不存消息内容。

读路径上，`ConversationController` 和 `AdminController` 各自直连 `redisTemplate` 查 `conversation:{convId}`，逻辑重复，且都没有 Redis-miss 回退。

### 1.2 触发问题

用户 7 天未继续某个会话，Redis key 自然过期删除。用户下次登录，`conversation_sessions` 里会话壳还在（标题可见），但点开后消息内容为空——历史"凭空消失"，实际是设计如此，非 bug。

### 1.3 遗留死代码

`Conversation` 实体 / `conversations` 表 / `ConversationService`（question+answer 合并存一行）是 2026-06-10 记忆系统重构前的旧 LTM 实现，重构后已无调用点（grep 确认 `ConversationService` 无任何注入/调用），表里剩 9 条旧数据，格式与现有 Redis 消息格式（按 role 拆分）不兼容。

### 1.4 目标

- 聊天消息原文持久化到 MySQL，Redis 过期不再丢数据。
- 热路径（喂给 LLM 的上下文加载）性能不劣化——Redis 命中时零 DB 开销。
- 收敛 `ConversationController` / `AdminController` 重复的 Redis 直连读逻辑。
- 清理不再使用的旧 LTM 表/代码。

### 1.5 非目标（YAGNI）

- 不改 STM 压缩摘要（`ContextCompressor`）、LTM 事实抽取（`UserMemoryFact`）逻辑，两者不变。
- 不做消息历史的分页/搜索 API（超出本次问题范围）。
- 不做写入失败重试队列（量级小，失败大概率是 DB 故障，重试无意义）。
- 不迁移旧 `conversations` 表的 9 条数据（格式不兼容，价值低——那些会话早已过 TTL，用户端本来就不可见）。

---

## 2. 总体架构

```
写路径（MemoryManager.record，不变的同步部分 + 新增异步部分）
  ├─ 同步：ConversationMemory.appendAndEvictIfNeeded()  → Redis（不变）
  └─ 异步：MessagePersistenceService.saveAsync()         → conversation_messages 表（新增）
           @Async("memoryExecutor")，复用现有异步线程池，fire-and-forget

读路径（ConversationMemory.loadHistory，改造为 cache-aside）
  Redis hit  → 直接返回（不变，热路径零 DB 开销）
  Redis miss → MessagePersistenceService.loadFromDb(convId)
               → 转换为 List<Map<role,content,timestamp>>
               → 回填 Redis（重新计 7 天 TTL）
               → 返回

展示层收敛
  ConversationController.getConversationsFromRedis()  ─┐
  AdminController.getAllConversations()                ├─ 都改为调用 ConversationMemory.loadHistory(convId)
                                                        ─┘  不再各自直连 redisTemplate
```

---

## 3. 数据模型

### 3.1 新表 `conversation_messages`

```sql
CREATE TABLE conversation_messages (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  conv_id    VARCHAR(36)  NOT NULL,
  seq        INT          NOT NULL,
  role       VARCHAR(20)  NOT NULL,
  content    TEXT         NOT NULL,
  created_at DATETIME(6)  NOT NULL,
  INDEX idx_conv_seq (conv_id, seq)
);
```

- `conv_id`：对应 `conversation_sessions.id`。**不建外键约束**——与项目现有表风格一致（`conversation_sessions` 等均无外键），`ddl-auto: update` 场景下外键会增加迁移摩擦。一致性由应用层保证（`conv_id` 恒来自 `SessionManager` 分配的合法会话）。
- `seq`：会话内消息序号，从 0 递增，用于恢复顺序（`created_at` 精度在高并发下可能重复，`seq` 兜底排序）。
- `role` / `content` / `created_at` 与 Redis 里 `Map<role,content,timestamp>` 字段一一对应，读写之间零格式转换逻辑（只需 `timestamp` 字符串 ↔ `DATETIME` 互转）。

### 3.2 废弃对象

删除（非软删除，代码库中确认无引用）：
- `Conversation` 实体（`model/Conversation.java`）
- `ConversationRepository`
- `ConversationService`
- `conversations` 表（连带一次性建表迁移里的 DROP，或依赖 `ddl-auto` 不再建表——需确认项目是否已有独立的 DDL 迁移脚本管理机制，若无则表会残留在库中直到手动清理，属已知遗留，不阻塞本次功能上线）

---

## 4. 组件改动清单

| 组件 | 改动类型 | 说明 |
|---|---|---|
| `ConversationMessageEntity` | 新增 | 映射 `conversation_messages` 表 |
| `ConversationMessageRepository` | 新增 | `findByConvIdOrderBySeqAsc`、`countByConvId`（算下一个 seq 用） |
| `MessagePersistenceService` | 新增 | `saveAsync(convId, question, answer)`：写 user+assistant 两行；`loadFromDb(convId)`：查全量按 seq 排序 |
| `MemoryManager.record()` | 改动 | 在现有 `appendAndEvictIfNeeded` 之后，追加调用 `messagePersistenceService.saveAsync(...)` |
| `ConversationMemory.loadHistory()` | 改动 | Redis miss 时回源 `MessagePersistenceService.loadFromDb`，回填 Redis |
| `ConversationController.getConversationsFromRedis()` | 改动 | 去掉直连 `redisTemplate` 的逻辑，改调 `conversationMemory.loadHistory(convId)` |
| `AdminController.getAllConversations()` | 改动 | 同上 |
| `Conversation` / `ConversationRepository` / `ConversationService` | 删除 | 死代码清理 |

---

## 5. 错误处理

| 场景 | 处理 |
|---|---|
| 异步写 MySQL 失败（`saveAsync` 抛异常） | 记 ERROR 日志，不影响主响应（用户已拿到回答）。不重试、不入死信队列——量级小，失败大概率是 DB 故障，重试意义有限 |
| Redis 回填失败（网络抖动） | 吞掉异常，直接返回 DB 查到的结果，不影响本次请求；下次请求 Redis 仍 miss，会再次触发回填（自愈，无需人工介入） |
| Redis/MySQL 数据不一致 | 理论上不存在竞态：只有 Redis 过期（key 不存在）才会查 MySQL，而此时异步写早已完成（TTL 是 7 天级别，写入是毫秒级别，无需处理"写入进行中同时读到旧数据"的窗口） |

---

## 6. 逐出（eviction）机制不受影响

`ConversationMemory.appendAndEvictIfNeeded` 在 token 预算超限时，把最老消息逐出到 `pending_compress`，交给 `ContextCompressor` 异步压缩进 STM 摘要——这个机制处理的是"喂给 LLM 的上下文窗口"，与本次改动的"可查询历史"是两回事。逐出后消息从 Redis 的 `history` list 中移除，但 MySQL 侧已经有独立的全量副本（写入时机是"消息产生时"，不是"消息还在 Redis 窗口内时"），查询历史不受逐出影响。

---

## 7. 测试要点

- **Unit**：`MessagePersistenceService.saveAsync` 落库字段正确性、`seq` 递增正确性（并发写同一 convId 时不重复/不跳号——需要确认是否要加锁；预期量级下单会话内消息基本串行产生，暂不加锁，若后续发现并发问题再补）
- **Unit**：`ConversationMemory.loadHistory` 在 Redis miss 时正确调用 `loadFromDb` 并回填 Redis（mock repository + mock RedisTemplate 验证 `set` 被调用且 TTL 正确）
- **Integration**：完整走一轮对话 → 手动 `DEL` Redis key 模拟过期 → 再次 `GET /api/v1/users/conversation` → 验证返回内容与原始一致
- **回归**：确认 `ConversationController` / `AdminController` 改造后原有的时间范围过滤（`start_date`/`end_date`）逻辑不受影响

---

## 8. 演进路线（本次不做）

- 消息历史分页 API（当前全量加载，会话消息量大时可能需要分页）
- 消息内容归档/冷存储策略（超过某个时间阈值的历史消息迁移到低成本存储）
- 旧 `conversations` 表彻底 DROP 的独立清理任务（如项目引入了 Flyway/Liquibase 等迁移工具再统一处理）
