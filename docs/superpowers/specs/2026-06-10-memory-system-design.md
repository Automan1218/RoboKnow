# RoboKnow 记忆系统设计（长短期对话记忆重构）

> 状态：设计待评审
> 日期：2026-06-10
> 范围：把现有散落在 `ReactAgentService` 的记忆逻辑，重构为「门面 + 5 组件」架构，落到 RoboKnow 企业 Web 多租户环境。
> 不含代码。

---

## 1. 背景与目标

### 1.1 现状
现有记忆逻辑全部塞在 `ReactAgentService` 里，三层混在一起：
- **STM**：Redis `conversation:{id}`，消息数 > 20 触发一次性 LLM 压缩，存 `conversation:{id}:stm_summary`。
- **LTM**：MySQL `conversations` 表，每轮异步生成一句话摘要，新消息注入最近 3 条。
- **会话**：Redis `user:{userId}:current_conversation`，7 天过期才重置。

问题：
1. 记忆逻辑与 Agent 逻辑耦合，`ReactAgentService` 职责过重。
2. STM 按"消息条数"淘汰，不对齐真实 token 窗口。
3. LTM 注入无相关性过滤，最近 3 条无条件塞进 system prompt。
4. Redis history 走 get-modify-set，并发同会话有写竞争。
5. 无统一 token 预算视图。
6. **无多会话支持**：`user:{userId}:current_conversation` 单 convId 7 天过期才重置，用户无法新建、切换或查看历史对话。

### 1.2 目标
- 用**门面模式**把记忆从 Agent 解耦：Agent 只调 `MemoryManager`，不感知底层。
- 短期记忆按 **token 预算**淘汰，对齐上下文窗口。
- 长期记忆从"流水账摘要"升级为"**去重的事实/偏好**"。
- 压缩与事实提取**移出同步请求链路**，控住 P99 延迟。
- 全链路**多租户隔离**，杜绝跨用户记忆串味。
- **多会话支持**：用户可新建、切换、删除对话，每个会话记忆相互隔离。

### 1.3 非目标（YAGNI）
- 不做向量化记忆检索（v2 再议，见 §6）。
- 不做用户可视化的记忆管理 UI（本期只做后端记忆引擎）。
- 不引入额外存储中间件（复用现有 Redis + MySQL）。

---

## 2. 核心设计判断：借职责，换载体

参考架构来自 **paicli —— 单机 / 单用户 / 单进程 CLI Agent**（证据：本地 JSON 文件、`-Dpaicli.memory.dir`、"进程退出即对话结束"）。RoboKnow 是 **多实例 / 多租户 / WebSocket 并发的企业 Web 系统**。

**结论：5 个组件的职责划分照搬；落地载体全换。**

| 参考做法（CLI） | 直接搬到 Web 的后果 | RoboKnow 做法 |
|---|---|---|
| LTM 存本地 `long_term_memory.json` | 多实例不共享、并发写互相覆盖、扩容/重启丢数据 | LTM → MySQL 表 |
| 内存 List 存短期消息 | 实例间不共享、实例挂掉即丢 | STM → Redis |
| "对话结束"触发 extractFacts | Web 进程不退出，无明确结束信号 | 显式触发点（§4.3） |
| 无租户概念 | 跨用户 / 跨租户记忆串味 = 数据泄漏 | 所有 key 强制带 userId（§7） |
| char/4 估算 token | 中文严重偏差，预算判断失真 | 真 tokenizer（jtokkit） |

---

## 3. 总体架构

```
                    ┌─────────────────────────────┐
   用户输入 ───────▶│      MemoryManager (门面)     │◀──── Agent 只调这一个
                    │  loadContext / record /      │
                    │  compressIfNeeded (内部)     │
                    └──────────────┬──────────────┘
                                   │ 协调
        ┌──────────────┬──────────┼──────────┬──────────────┐
        ▼              ▼          ▼          ▼              ▼
 ConversationMemory  TokenBudget ContextCompressor LongTermMemory MemoryRetriever
   (短期·Redis)      (预算·内存)  (压缩·异步LLM)    (长期·MySQL)   (检索·关键词+衰减)

           ┌──────────────────────────────────────┐
           │      SessionManager (会话生命周期)     │◀──── Controller 层调用
           │  create / list / switch / delete     │
           │  持久化：MySQL conversation_sessions  │
           └──────────────────────────────────────┘
```

Agent 侧只剩两个调用：
- `loadContext(userId, convId, userMessage)` → 返回拼好的上下文消息列表。
- `record(userId, convId, question, answer)` → 存交互，内部按需触发压缩/事实提取。

Controller 层（WebSocket/REST）通过 `SessionManager` 管理会话生命周期，解耦于记忆逻辑。

---

## 4. 组件设计

### 4.0 SessionManager（会话生命周期，新增）

**职责**：管理会话的创建/列表/切换/删除，与记忆逻辑完全解耦。Controller 层调 SessionManager，MemoryManager 只接受已存在的 `convId`。

**持久化**：MySQL 新表 `conversation_sessions`，关键字段：
- `id`（UUID PK，即 convId）
- `user_id`（索引，隔离用）
- `title`（会话标题，最长 100 字；首轮后异步 LLM 生成，失败则截取用户首条消息前 30 字）
- `status`（`active` / `archived`）
- `created_at` / `last_active_at`

**接口语义**：
- `createSession(userId)` → 生成 UUID convId，写 `conversation_sessions`，更新 Redis `user:{userId}:active_conversation`，返回 convId。
- `listSessions(userId)` → 按 `last_active_at DESC` 返回该用户所有 `active` 会话（含 title、时间戳），前端展示历史列表用。
- `switchSession(userId, convId)` → 校验 `conversation_sessions.user_id == userId`（防越权），更新 Redis `user:{userId}:active_conversation`。
- `deleteSession(userId, convId)` → 校验所有权，软删（status → `archived`），**显式清理** Redis `conversation:{convId}` 及 `conversation:{convId}:*` 所有 key（不等 TTL 自然过期，及时释放内存）。
- `getActiveConvId(userId)` → Redis 先查 `user:{userId}:active_conversation`；miss 则查 MySQL 最新 `active` 会话；均无则自动 `createSession()`（保证 Agent 调用路径不需要 null 判断）。

**WebSocket 接入**：客户端握手时传 `convId`（可选）。
- 有 `convId`：SessionManager 校验所有权后使用。
- 无 `convId`：`getActiveConvId()` 返回当前活跃会话或自动创建。

**自动标题生成**：首轮 `record()` 完成后，异步触发 `generateTitle(convId, firstUserMessage)`，5-10 字精炼，写回 `conversation_sessions.title`。失败静默降级（截断前 30 字）。

**Redis key 迁移**：旧 `user:{userId}:current_conversation` 改名为 `user:{userId}:active_conversation`，语义从"7 天才重置的单会话"变为"当前选中会话的指针"；由 `switchSession` / `deleteSession` 显式维护，而非依赖 TTL 被动重置。

### 4.1 ConversationMemory（短期记忆）
- **载体**：Redis `conversation:{convId}`，TTL 7 天。
- **淘汰策略**：FIFO + token 预算。当 `TokenBudget.getUsageRatio() > 0.8`，从头淘汰最旧消息，直到回到阈值下。
- **淘汰去向**：被淘汰消息不丢，进 Redis `conversation:{convId}:pending_compress` 缓冲，等异步压缩。
- **并发**：get-modify-set 改为 Redis 原子操作（list push / Lua 脚本），消除同会话写竞争。
- **取舍**：相比现状"消息数 > 20"，token 预算才真正对齐上下文窗口；FIFO 因对话天然时序，最旧≈最不相关，足够。

### 4.2 TokenBudget（Token 预算）
- **载体**：无状态计算组件（按会话即时算）。
- **职责**：`countTokens(messages)`、`getUsageRatio()`、`remaining()`。
- **tokenizer**：jtokkit，按模型选 encoding（`cl100k_base` / 对应模型）。**禁用 char/4 估算**——中文偏差大。
- **预算来源**：模型上下文窗口 × 安全系数（如 0.8），预留输出 token。
- **触发**：超 80% → MemoryManager 触发 ContextCompressor。

### 4.3 ContextCompressor（上下文压缩 + 事实提取）
- **载体**：异步（独立线程池 / `@Async`），**严禁挂同步请求链路**——Map-Reduce 多次 LLM 调用会炸 P99。
- **压缩（Map-Reduce）**：
  - Map：`pending_compress` 里旧消息按 5 条一组，每组独立 LLM 生成分片摘要（短文本摘要质量更高）。
  - Reduce：分片摘要合并为最终摘要；只有一片则直接用，省一次 LLM。
  - 结果写 `conversation:{convId}:stm_summary`（与现有 key 兼容）。
- **事实提取（extractFacts）**：从对话提炼用户偏好 / 项目配置 / 重要决策，写入 LongTermMemory。
  - **触发点（Web 适配，本设计定稿）**：
    - (b) **会话空闲超时**：会话 30 分钟无新消息，后台批量提取（用 Redis key 过期事件或定时扫描）。
    - (c) **增量提取**：每累计 N 轮（如 10 轮）触发一次增量提取，避免长会话久不沉淀。
  - 两者组合：长会话靠 (c) 持续沉淀，会话自然结束靠 (b) 收尾。

### 4.4 LongTermMemory（长期记忆）
- **载体**：MySQL 新表 `user_memory_facts`（不污染现有 `conversations` 表）。
- **语义升级**：从"每轮一句话摘要"升级为"**去重的事实/偏好**"。
- **隔离范围（定稿）**：**仅按 userId，私有不共享**。不带 orgTag 共享——规避刚修过的越权泄漏红线（参见 commit 2372503）。
- **去重**：写入前按内容指纹（归一化文本 hash / 或 LLM 判等）去重，"用户喜欢 Java"说三次只存一条。
- **即时持久化**：每次 store 落库（MySQL 事务，不是参考的 saveToDisk）。
- **与 conversations 表关系（定稿）**：`conversations` 表**保留**做审计/历史查询；记忆注入只用 `user_memory_facts`。两者并行，职责分离。

`user_memory_facts` 表关键字段（设计意图，非建表语句）：
- `user_id`（隔离键，索引）
- `content`（事实文本）
- `content_hash`（去重）
- `source_conversation_id`（溯源）
- `created_at` / `updated_at` / `hit_count`（供检索时间衰减用）

### 4.5 MemoryRetriever（记忆检索）
- **v1 策略（定稿）**：关键词匹配 + 时间衰减。
  - 按当前 userMessage 关键词匹配该 user 的 facts。
  - 时间衰减：越新权重越高（`hit_count` 可加成）。
  - 取 Top-K（如 3）注入，**有相关性过滤**——解决现状"无条件塞最近 3 条"的问题。
- **理由**：每用户 facts 数量少、文本短，关键词+recency 性价比最高，省 embedding 成本。
- **v2 选项**：facts 量级增大后，可复用现有 ES + 向量做语义检索（见 §6）。

### 4.6 MemoryManager（门面）
- **唯一对外入口**。Agent 不碰底层 5 组件。
- `loadContext(userId, convId, userMessage)`：
  1. system prompt（Agent 提供）
  2. MemoryRetriever 检索的相关 facts（LTM 注入）
  3. STM summary（如有）
  4. 最近 N 条原文（context window 内）
  5. 当前 userMessage
- `record(userId, convId, q, a)`：存交互 → `compressIfNeeded()`（查 TokenBudget，超阈值异步触发压缩）→ 异步增量事实提取计数。
- **收益**：`ReactAgentService` 彻底卸掉记忆职责，只管 ReAct 循环。

---

## 5. 数据流

**读路径（每轮请求，全部同步、零额外 LLM）**：
```
Agent → MemoryManager.loadContext()
      → ConversationMemory 取 Redis history（原子读）
      → MemoryRetriever 关键词+衰减选 Top-K facts
      → 拼装 [system][facts][stm_summary][recent N][user_msg]
      → 返回给 Agent
```

**写路径（每轮请求结束）**：
```
Agent → MemoryManager.record()
      → ConversationMemory 原子写入 Redis
      → TokenBudget.getUsageRatio()
         ├─ < 80%：结束
         └─ ≥ 80%：FIFO 淘汰 → pending_compress
                    → 【异步】ContextCompressor Map-Reduce → stm_summary
      → 增量轮次计数；达 N 轮 →【异步】extractFacts → LongTermMemory（去重落库）
```

**会话收尾（空闲超时）**：
```
定时扫描 / Redis 过期事件 → 会话 30min 无活动
   → 【异步】extractFacts 收尾 → LongTermMemory
```

**会话创建/切换流**：
```
前端 → ConversationController.createSession(userId)
      → SessionManager.createSession(userId)
         → 写 conversation_sessions（MySQL）
         → 写 user:{userId}:active_conversation（Redis）
      → 返回 convId 给前端

前端 → ConversationController.switchSession(userId, convId)
      → SessionManager.switchSession(userId, convId)
         → 校验 user_id 所有权（防越权）
         → 更新 user:{userId}:active_conversation（Redis）

WebSocket 握手 → ChatHandler.handleMessage(userId, convId?)
      → SessionManager.getActiveConvId(userId)
         → Redis hit：直接用
         → Redis miss：查 MySQL 最新 active 会话 / 自动 createSession
      → Agent 拿到 convId 后调 MemoryManager.loadContext(userId, convId, msg)
```

---

## 6. 演进路线
- **v1（本期）**：门面 + 5 组件，Redis STM + MySQL LTM，关键词检索，异步压缩/提取。
- **v2**：facts 向量化，复用 ES `knowledge_base` 同款向量检索，做语义召回；记忆相关性可量化评测。
- **v3**：用户可视化记忆管理（查看/删除/置顶 facts），人工干预记忆。

---

## 7. 生产风险与红线

| 风险 | 应对 |
|---|---|
| **跨租户记忆泄漏** | 所有 Redis key / MySQL 查询强制带 userId；facts 仅私有，不按 orgTag 共享。回归测试覆盖越权场景。 |
| **会话越权访问** | `switchSession` / `deleteSession` 强校验 `conversation_sessions.user_id == 当前用户`；WebSocket 握手传入的 convId 同样校验所有权。 |
| **删除后 Redis 残留** | `deleteSession` 显式 DEL `conversation:{convId}` 及所有子 key，不依赖 TTL，防止 key 泄漏到下一个创建相同 convId 的用户（UUID 碰撞概率极低但防御性清理）。 |
| **压缩拖慢 P99** | Map-Reduce 全异步，独立线程池，与请求链路隔离；线程池满则跳过本次压缩（降级），不阻塞用户。 |
| **成本失控** | 最坏一轮 = STM 压缩 + 事实提取多次 LLM。用增量计数 + 节流，避免每轮都触发；压缩走便宜模型。 |
| **并发写竞争** | Redis 原子操作 / Lua 脚本替换 get-modify-set。 |
| **tokenizer 不准** | jtokkit 真分词，按模型选 encoding，禁用 char/4。 |
| **事实污染**（错误/过期记忆被反复召回） | facts 带 created_at 衰减；去重；后续可加置信度/失效机制。 |
| **异步失败静默丢失** | 压缩/提取失败记日志告警，不影响主链路；pending_compress 保留待重试。 |

---

## 8. 迁移与兼容
- 保留 `conversation:{id}` 与 `conversation:{id}:stm_summary` 两个 Redis key，平滑兼容现有数据。
- `conversations` 表不动，新增 `user_memory_facts` 表（JPA 自动建表）。
- 重构分阶段：先抽门面（行为不变）→ 再换 token 预算淘汰 → 再上事实提取与检索。每步独立可回滚。
- `user:{userId}:current_conversation` → `user:{userId}:active_conversation`：启动时读旧 key 做一次迁移写，旧 key 设 1 天 TTL 自动消亡，无需停机。
- `conversation_sessions` 表新建；存量对话无 session 记录的用户，首次触发 `getActiveConvId()` 时自动创建默认会话并关联历史 convId。

---

## 9. 已定稿的四个关键决策
1. **事实提取触发点**：(b) 会话空闲 30min 超时 + (c) 每 10 轮增量，组合触发。
2. **LongTermMemory 隔离**：仅 userId，私有不共享（不带 orgTag）。
3. **conversations 表**：保留做审计；新 `user_memory_facts` 表做注入，职责分离。
4. **多会话**：SessionManager 独立组件，`conversation_sessions` MySQL 表持久化，Redis `active_conversation` key 存指针；WebSocket 握手可传 convId，缺省自动取活跃会话；会话删除显式清理 Redis。
