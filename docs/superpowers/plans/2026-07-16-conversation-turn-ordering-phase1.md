# 会话轮次顺序化 — 阶段一实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 关闭 `2026-07-16-conversation-concurrency-consistency-design.md` 诊断的消息丢失/乱序窗口——同一 convId 的连续消息严格按到达顺序处理,回答先落库再推送,receipt 幂等。

**Architecture:** WebSocket 接收消息后,`ConversationCommandService` 用一次同步 MySQL 短事务分配单调 `turnSeq`、写入 `PENDING` turn 和 user 消息,再返回 `accepted`。`ConversationTurnDispatcher` 保证每个 convId 同一时刻最多一个后台线程在 drain 该会话的 PENDING 队列。`ReactAgentService` 在算出最终答案后先同步提交 DB(turn→COMPLETE + assistant 消息),再做打字机效果推送和 completion 通知。

**Tech Stack:** Spring Boot/JPA(现有 `ddl-auto: update`,无迁移框架)、MySQL 8、Redis(STM/LTM 不动)、JUnit 5 + `@DataJpaTest` 真实 MySQL IT 测试(沿用 `ConversationMessageRepositoryIT` 模式)。

## Global Constraints

- 不引入 Kafka/RabbitMQ、不引入 Redis Stream、不引入分布式租约(Lua fencing)——当前部署是单进程 `mvn spring-boot:run`,原方案 §4/§6.4/§7.1 的分布式调度是为多实例场景设计的,现在上会引入不成比例的复杂度和风险。用**进程内按 convId 串行的 dispatcher**达到同样的"同一会话严格串行"不变量;数据库仍是唯一真相源,重启后可从 `PENDING/PROCESSING` turn 恢复,不依赖内存状态。
- 原方案第 8.2 节"生成完整 finalAnswer → 提交 → 推送"读起来像是要求缓冲整个回答再推送,但代码验证 `ReactAgentService.streamText()` 本来就是对**已经算完的** `finalAnswer` 字符串做客户端打字机效果(逐 30 字符 `Thread.sleep(25)`),不是真正的 LLM 增量 token 流。因此"先 DB 提交、再推送"只需把一次同步 SQL insert 插到打字机效果之前,对用户体感延迟的影响是毫秒级,不是需要架构妥协的取舍。ReAct 循环里的 THINKING/ACTING/OBSERVING 事件不受影响,继续实时推送。
- 只做原方案的**阶段一**(turnSeq/outbox/串行 worker/先提交后推送/幂等 receipt)。阶段二(Redis 版本化摘要 + Lua CAS)和阶段三(reconciliation + 监控指标)不在本计划范围——原方案自己承认"完成此阶段后,即使暂时沿用旧摘要,消息不丢和回答顺序问题已解决",阶段一是自洽、可独立验收的增量。
- 所有新表通过 JPA `ddl-auto: update` 自动建表/加约束,不写手工 migration(项目现状如此)。给 `conversation_messages` 加 `(conv_id, seq)` 唯一约束前必须先审计现有数据无重复。
- 每个任务写完就跑对应测试,禁止攒到最后一次性验证。

---

### Task 1: WebSocketSessionRegistry — 把连接表从 Handler 私有字段提出来

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/handler/WebSocketSessionRegistry.java`
- Modify: `src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/handler/WebSocketSessionRegistryTest.java`

**Interfaces:**
- Produces: `WebSocketSessionRegistry.register(String userId, WebSocketSession session)`、`unregister(String userId)`、`Optional<WebSocketSession> get(String userId)` — 后续 dispatcher 靠这个从 userId 反查活跃连接。

- [ ] **Step 1: 写 registry 类**

```java
package com.yizhaoqi.roboknow.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionRegistry {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregister(String userId) {
        sessions.remove(userId);
    }

    public Optional<WebSocketSession> get(String userId) {
        WebSocketSession session = sessions.get(userId);
        return (session != null && session.isOpen()) ? Optional.of(session) : Optional.empty();
    }
}
```

- [ ] **Step 2: `ChatWebSocketHandler` 改用它**,删除 `private final ConcurrentHashMap<String, WebSocketSession> sessions` 字段,构造函数注入 `WebSocketSessionRegistry`,`afterConnectionEstablished` 里 `sessions.put(username, session)` 改成 `sessionRegistry.register(username, session)`,`afterConnectionClosed` 里 `sessions.remove(userId)` 改成 `sessionRegistry.unregister(userId)`。

- [ ] **Step 3: 写测试**

```java
package com.yizhaoqi.roboknow.handler;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketSessionRegistryTest {

    @Test
    void getReturnsEmptyWhenNeverRegistered() {
        var registry = new WebSocketSessionRegistry();
        assertTrue(registry.get("nobody").isEmpty());
    }

    @Test
    void registerThenGetReturnsSessionWhenOpen() {
        var registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        registry.register("alice", session);
        assertEquals(session, registry.get("alice").orElseThrow());
    }

    @Test
    void getReturnsEmptyWhenSessionClosed() {
        var registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        registry.register("bob", session);
        assertTrue(registry.get("bob").isEmpty());
    }

    @Test
    void unregisterRemovesSession() {
        var registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        registry.register("carol", session);
        registry.unregister("carol");
        assertTrue(registry.get("carol").isEmpty());
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=WebSocketSessionRegistryTest`
Expected: 4/4 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/handler/WebSocketSessionRegistry.java src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java src/test/java/com/yizhaoqi/roboknow/handler/WebSocketSessionRegistryTest.java
git commit -m "refactor(ws): extract session map into WebSocketSessionRegistry bean"
```

---

### Task 2: `ConversationTurn` 实体 + repository

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/model/ConversationTurn.java`
- Create: `src/main/java/com/yizhaoqi/roboknow/repository/ConversationTurnRepository.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/repository/ConversationTurnRepositoryIT.java`

**Interfaces:**
- Produces: `ConversationTurn.Status` 枚举 `PENDING/PROCESSING/COMPLETE/FAILED/CANCELLED`；repository 方法 `findByConvIdAndRequestId`、`findFirstByConvIdAndStatusOrderByTurnSeqAsc`、`existsByConvIdAndStatusIn`。

- [ ] **Step 1: 实体**

```java
package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_turns",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_conv_turn_seq", columnNames = {"conv_id", "turn_seq"}),
                @UniqueConstraint(name = "uk_conv_request_id", columnNames = {"conv_id", "request_id"})
        },
        indexes = {
                @Index(name = "idx_turn_conv_status", columnList = "conv_id,status")
        })
public class ConversationTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conv_id", nullable = false, length = 36)
    private String convId;

    @Column(name = "turn_seq", nullable = false)
    private int turnSeq;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "user_content", nullable = false, columnDefinition = "TEXT")
    private String userContent;

    @Column(name = "assistant_content", columnDefinition = "TEXT")
    private String assistantContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempt_token", length = 36)
    private String attemptToken;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_code", length = 255)
    private String errorCode;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        PENDING, PROCESSING, COMPLETE, FAILED, CANCELLED
    }
}
```

- [ ] **Step 2: repository**

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, Long> {

    Optional<ConversationTurn> findByConvIdAndRequestId(String convId, String requestId);

    Optional<ConversationTurn> findFirstByConvIdAndStatusOrderByTurnSeqAsc(
            String convId, ConversationTurn.Status status);

    boolean existsByConvIdAndStatusIn(String convId, List<ConversationTurn.Status> statuses);

    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'PROCESSING', t.attemptToken = :attemptToken, " +
           "t.startedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.status = 'PENDING'")
    int claimForProcessing(@Param("id") Long id, @Param("attemptToken") String attemptToken);

    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'COMPLETE', t.assistantContent = :assistantContent, " +
           "t.completedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.attemptToken = :attemptToken " +
           "AND t.status = 'PROCESSING'")
    int completeIfOwned(@Param("id") Long id, @Param("attemptToken") String attemptToken,
                         @Param("assistantContent") String assistantContent);

    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'FAILED', t.errorCode = :errorCode, " +
           "t.completedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.attemptToken = :attemptToken " +
           "AND t.status = 'PROCESSING'")
    int failIfOwned(@Param("id") Long id, @Param("attemptToken") String attemptToken,
                     @Param("errorCode") String errorCode);
}
```

- [ ] **Step 3: IT 测试**(参照 `ConversationMessageRepositoryIT` 风格,真实 MySQL dev 库,事务自动回滚)

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ConversationTurnRepositoryIT {

    @Autowired
    private ConversationTurnRepository repository;

    private ConversationTurn build(String convId, int turnSeq, String requestId) {
        ConversationTurn t = new ConversationTurn();
        t.setConvId(convId);
        t.setTurnSeq(turnSeq);
        t.setRequestId(requestId);
        t.setUserContent("hello");
        t.setReceivedAt(LocalDateTime.now());
        return t;
    }

    @Test
    void duplicateTurnSeqForSameConvRejected() {
        repository.saveAndFlush(build("it-turn-conv-1", 1, "req-1"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(build("it-turn-conv-1", 1, "req-2")));
    }

    @Test
    void duplicateRequestIdForSameConvRejected() {
        repository.saveAndFlush(build("it-turn-conv-2", 1, "dup-req"));
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(build("it-turn-conv-2", 2, "dup-req")));
    }

    @Test
    void claimForProcessingOnlySucceedsFromPending() {
        ConversationTurn t = repository.saveAndFlush(build("it-turn-conv-3", 1, "req-3"));

        int firstClaim = repository.claimForProcessing(t.getId(), "token-a");
        assertEquals(1, firstClaim);

        int secondClaim = repository.claimForProcessing(t.getId(), "token-b");
        assertEquals(0, secondClaim, "already PROCESSING turn must not be claimable again");
    }

    @Test
    void completeIfOwnedRejectsWrongAttemptToken() {
        ConversationTurn t = repository.saveAndFlush(build("it-turn-conv-4", 1, "req-4"));
        repository.claimForProcessing(t.getId(), "token-real");

        int wrongTokenResult = repository.completeIfOwned(t.getId(), "token-fake", "answer");
        assertEquals(0, wrongTokenResult);

        int rightTokenResult = repository.completeIfOwned(t.getId(), "token-real", "answer");
        assertEquals(1, rightTokenResult);
    }

    @Test
    void findFirstByConvIdAndStatusOrderByTurnSeqAscReturnsEarliestPending() {
        repository.saveAndFlush(build("it-turn-conv-5", 3, "req-5c"));
        repository.saveAndFlush(build("it-turn-conv-5", 1, "req-5a"));
        repository.saveAndFlush(build("it-turn-conv-5", 2, "req-5b"));

        var earliest = repository.findFirstByConvIdAndStatusOrderByTurnSeqAsc(
                "it-turn-conv-5", ConversationTurn.Status.PENDING);
        assertTrue(earliest.isPresent());
        assertEquals(1, earliest.get().getTurnSeq());
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ConversationTurnRepositoryIT`
Expected: 5/5 PASS(真实连 dev MySQL,`ddl-auto=update` 自动建表)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/model/ConversationTurn.java src/main/java/com/yizhaoqi/roboknow/repository/ConversationTurnRepository.java src/test/java/com/yizhaoqi/roboknow/repository/ConversationTurnRepositoryIT.java
git commit -m "feat(conversation): add conversation_turns table for ordered turn tracking"
```

---

### Task 3: `ConversationSession.nextTurnSeq` + 原子分配

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/model/ConversationSession.java`
- Modify: `src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/repository/ConversationSessionTurnSeqIT.java`

**Interfaces:**
- Produces: `ConversationSessionRepository.lockForUpdate(String convId)` — 返回悲观写锁下的会话行,调用方在同一事务里 `session.setNextTurnSeq(session.getNextTurnSeq()+1)` 后 `save`。

- [ ] **Step 1: 加字段**

在 `ConversationSession.java` 里 `roundCount` 字段后加:

```java
    @Column(name = "next_turn_seq", nullable = false)
    private int nextTurnSeq = 0;
```

- [ ] **Step 2: repository 加悲观锁查询**

```java
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConversationSession s WHERE s.id = :convId")
    Optional<ConversationSession> lockForUpdate(@Param("convId") String convId);
```

需要新增 import `org.springframework.data.jpa.repository.Lock`。

- [ ] **Step 3: 并发分配测试**——20 个线程对同一 convId 并发 `lockForUpdate`+自增+`save`(各自独立事务),验证最终 `nextTurnSeq==20` 且没有两个线程拿到同一个分配值。

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ConversationSessionTurnSeqIT {

    @Autowired
    private ConversationSessionRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void concurrentAllocationProducesNoDuplicatesAndReachesExpectedTotal() throws Exception {
        String convId = "it-turnseq-conv-1";
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        repository.saveAndFlush(session);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> allocated = new ConcurrentLinkedQueue<>();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ignored) {}
                Integer result = tx.execute(status -> {
                    ConversationSession s = repository.lockForUpdate(convId).orElseThrow();
                    int allocatedSeq = s.getNextTurnSeq() + 1;
                    s.setNextTurnSeq(allocatedSeq);
                    repository.save(s);
                    return allocatedSeq;
                });
                allocated.add(result);
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(threads, allocated.size());
        assertEquals(threads, Set.copyOf(allocated).size(), "no two threads may receive the same turnSeq");

        ConversationSession finalState = repository.findById(convId).orElseThrow();
        assertEquals(threads, finalState.getNextTurnSeq());
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ConversationSessionTurnSeqIT`
Expected: PASS,20 个分配值互不相同,`nextTurnSeq` 最终等于 20

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/model/ConversationSession.java src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java src/test/java/com/yizhaoqi/roboknow/repository/ConversationSessionTurnSeqIT.java
git commit -m "feat(conversation): atomic per-session turnSeq allocation via pessimistic lock"
```

---

### Task 4: `ConversationMessage` 确定性 seq + 唯一约束

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/model/ConversationMessage.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/repository/ConversationMessageUniqueSeqIT.java`

**Interfaces:**
- Consumes: 无(纯 schema 变更)
- Produces: `(conv_id, seq)` 唯一约束——后续 Task 5/6 写入时用 `turnSeq*2`/`turnSeq*2+1` 作为 seq,不再用 `countByConvId()`。

- [ ] **Step 1: 先审计现有数据无重复**(防止加约束时 `ddl-auto=update` 失败)

Run:
```bash
docker exec mysql mysql -uroot -pRoboKnow2025 RoboKnow -N -e "SELECT conv_id, seq, COUNT(*) c FROM conversation_messages GROUP BY conv_id, seq HAVING c > 1"
```
Expected: 空结果。如果非空,先手工去重(保留每组最早 id)再继续。

- [ ] **Step 2: 加唯一约束**

`ConversationMessage.java` 的 `@Table` 注解改为:

```java
@Table(name = "conversation_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_seq", columnNames = {"conv_id", "seq"}),
        indexes = {
                @Index(name = "idx_conv_seq", columnList = "conv_id,seq")
        })
```

- [ ] **Step 3: 测试唯一约束生效**

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.show-sql=false"
})
class ConversationMessageUniqueSeqIT {

    @Autowired
    private ConversationMessageRepository repository;

    @Test
    void duplicateConvIdSeqRejected() {
        ConversationMessage m1 = new ConversationMessage();
        m1.setConvId("it-uniqseq-conv-1");
        m1.setSeq(0);
        m1.setRole("user");
        m1.setContent("hi");
        m1.setCreatedAt(LocalDateTime.now());
        repository.saveAndFlush(m1);

        ConversationMessage m2 = new ConversationMessage();
        m2.setConvId("it-uniqseq-conv-1");
        m2.setSeq(0);
        m2.setRole("assistant");
        m2.setContent("hi back");
        m2.setCreatedAt(LocalDateTime.now());

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(m2));
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ConversationMessageUniqueSeqIT`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/model/ConversationMessage.java src/test/java/com/yizhaoqi/roboknow/repository/ConversationMessageUniqueSeqIT.java
git commit -m "feat(conversation): enforce unique (conv_id, seq) on conversation_messages"
```

---

### Task 5: `ConversationCommandService` — 同步幂等 accept

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/service/ConversationCommandService.java`
- Create: `src/main/java/com/yizhaoqi/roboknow/service/TurnAccepted.java`(简单 record)
- Test: `src/test/java/com/yizhaoqi/roboknow/service/ConversationCommandServiceIT.java`

**Consumes:** `ConversationTurnRepository`(Task 2)、`ConversationSessionRepository.lockForUpdate`(Task 3)、`ConversationMessageRepository`(既有)。
**Produces:** `TurnAccepted acceptMessage(String userId, String convId, String requestId, String userMessage)` — Task 8(WebSocket handler)和 Task 9(dispatcher)依赖这个签名。

- [ ] **Step 1: `TurnAccepted` record**

```java
package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationTurn;

public record TurnAccepted(int turnSeq, String requestId, ConversationTurn.Status status) {
}
```

- [ ] **Step 2: `ConversationCommandService`**

```java
package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 接收用户消息的唯一入口：同步短事务里分配 turnSeq、写 PENDING turn 和 user 消息。
 * 事务提交后才允许调用方返回 accepted——DB 是消息是否被接受的唯一真相源。
 */
@Service
public class ConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCommandService.class);

    private final ConversationSessionRepository sessionRepository;
    private final ConversationTurnRepository turnRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationCommandService(ConversationSessionRepository sessionRepository,
                                       ConversationTurnRepository turnRepository,
                                       ConversationMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public TurnAccepted acceptMessage(String userId, String convId, String requestId, String userMessage) {
        // 幂等：同一 (convId, requestId) 重复提交返回已有 turn，不重复分配 seq / 不重复插入消息
        Optional<ConversationTurn> existing = turnRepository.findByConvIdAndRequestId(convId, requestId);
        if (existing.isPresent()) {
            ConversationTurn t = existing.get();
            logger.debug("Duplicate requestId={} for convId={}, returning existing turnSeq={}",
                    requestId, convId, t.getTurnSeq());
            return new TurnAccepted(t.getTurnSeq(), requestId, t.getStatus());
        }

        ConversationSession session = sessionRepository.lockForUpdate(convId)
                .orElseThrow(() -> new CustomException("Session not found", HttpStatus.NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }

        int turnSeq = session.getNextTurnSeq() + 1;
        session.setNextTurnSeq(turnSeq);
        sessionRepository.save(session);

        ConversationTurn turn = new ConversationTurn();
        turn.setConvId(convId);
        turn.setTurnSeq(turnSeq);
        turn.setRequestId(requestId);
        turn.setUserContent(userMessage);
        turn.setStatus(ConversationTurn.Status.PENDING);
        turn.setReceivedAt(LocalDateTime.now());
        turnRepository.save(turn);

        ConversationMessage userRow = new ConversationMessage();
        userRow.setConvId(convId);
        userRow.setSeq(turnSeq * 2);
        userRow.setRole("user");
        userRow.setContent(userMessage);
        userRow.setCreatedAt(LocalDateTime.now());
        messageRepository.save(userRow);

        logger.info("Accepted turn convId={} turnSeq={} requestId={}", convId, turnSeq, requestId);
        return new TurnAccepted(turnSeq, requestId, ConversationTurn.Status.PENDING);
    }
}
```

- [ ] **Step 3: 测试**——重点验证幂等和并发不重复分配

```java
package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ConversationCommandServiceIT {

    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationSessionRepository sessionRepository;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationMessageRepository messageRepository;

    private String convId;

    @BeforeEach
    void setUp() {
        convId = "it-cmd-conv-" + UUID.randomUUID();
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);
    }

    @Test
    void duplicateRequestIdReturnsSameTurnSeqWithoutDuplicateRows() {
        String requestId = "req-dup-1";
        TurnAccepted first = commandService.acceptMessage("alice", convId, requestId, "hello");
        TurnAccepted second = commandService.acceptMessage("alice", convId, requestId, "hello");

        assertEquals(first.turnSeq(), second.turnSeq());
        assertEquals(1, turnRepository.findByConvIdAndRequestId(convId, requestId).stream().count());
        assertEquals(1, messageRepository.findByConvIdOrderBySeqAsc(convId).size());
    }

    @Test
    void concurrentAcceptsProduceContiguousTurnSeqsNoGapsNoDuplicates() throws Exception {
        int n = 15;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TurnAccepted>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < n; i++) {
            String requestId = "req-conc-" + i;
            futures.add(pool.submit(() -> {
                go.await();
                return commandService.acceptMessage("alice", convId, requestId, "msg-" + requestId);
            }));
        }
        go.countDown();

        Set<Integer> turnSeqs = new java.util.HashSet<>();
        for (Future<TurnAccepted> f : futures) {
            turnSeqs.add(f.get(10, TimeUnit.SECONDS).turnSeq());
        }
        pool.shutdown();

        assertEquals(n, turnSeqs.size(), "every concurrent accept must get a unique turnSeq");
        assertEquals(Set.of(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15), turnSeqs,
                "turnSeqs must be contiguous 1..n, no gaps");

        // user 消息 seq 必须是 turnSeq*2，互不相同
        long distinctSeqs = messageRepository.findByConvIdOrderBySeqAsc(convId).stream()
                .map(m -> m.getSeq()).distinct().count();
        assertEquals(n, distinctSeqs);
    }

    @Test
    void wrongUserRejected() {
        assertThrows(RuntimeException.class,
                () -> commandService.acceptMessage("mallory", convId, "req-x", "hi"));
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ConversationCommandServiceIT`
Expected: 3/3 PASS,尤其 `concurrentAcceptsProduceContiguousTurnSeqsNoGapsNoDuplicates` 必须稳定通过(这是本阶段最核心的正确性保证)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/service/ConversationCommandService.java src/main/java/com/yizhaoqi/roboknow/service/TurnAccepted.java src/test/java/com/yizhaoqi/roboknow/service/ConversationCommandServiceIT.java
git commit -m "feat(conversation): synchronous idempotent turn acceptance with atomic turnSeq"
```

---

### Task 6: `ConversationTurnDispatcher` — 进程内按 convId 串行

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/service/ConversationTurnDispatcher.java`
- Modify: `src/main/java/com/yizhaoqi/roboknow/config/AsyncConfig.java`(新增 `turnWorkerExecutor` bean)
- Test: `src/test/java/com/yizhaoqi/roboknow/service/ConversationTurnDispatcherTest.java`

**Consumes:** 一个 `Consumer<String convId>`(实际的 turn 处理逻辑,Task 7 会传入真正实现;这里先用可注入的处理函数保持单元可测)。
**Produces:** `void submit(String convId)` — 保证同一 convId 同一时刻最多一个 drain 线程在跑;`submit` 立即返回,不阻塞调用方。

- [ ] **Step 1: `AsyncConfig` 加一个独立线程池**(跟 `memoryExecutor` 分开,turn 处理不能被 best-effort 丢弃)

```java
    @Bean(name = "turnWorkerExecutor")
    public Executor turnWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("turn-worker-");
        // 关键任务：队列满时调用者线程执行，绝不静默丢弃（对比 memoryExecutor 的丢弃策略）
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
```

- [ ] **Step 2: dispatcher**

```java
package com.yizhaoqi.roboknow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 保证同一 convId 的 turn 处理严格串行，不同 convId 并行。
 * 单进程内的“运行租约”：draining.get(convId)==true 期间，同一 convId 的重复 submit 都是 no-op，
 * 因为已经在跑的 drain 循环会自己捞出新出现的 PENDING turn，不需要第二个线程。
 * 崩溃恢复不依赖这个 Map：重启后 PENDING/PROCESSING turn 仍在数据库里，
 * 下一次任意消息触发 submit 时会被同一个 drain 循环捡起来处理。
 */
@Component
public class ConversationTurnDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnDispatcher.class);

    private final ConcurrentHashMap<String, AtomicBoolean> draining = new ConcurrentHashMap<>();
    private final Executor executor;
    private volatile Consumer<String> turnProcessor;

    public ConversationTurnDispatcher(@Qualifier("turnWorkerExecutor") Executor executor) {
        this.executor = executor;
    }

    /** 由 Task 7 在应用启动后注入真正的单轮处理逻辑；测试里可以注入 stub。 */
    public void setTurnProcessor(Consumer<String> turnProcessor) {
        this.turnProcessor = turnProcessor;
    }

    public void submit(String convId) {
        AtomicBoolean flag = draining.computeIfAbsent(convId, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            executor.execute(() -> drain(convId, flag));
        }
        // else: 已有 drain 循环在跑，它会自己发现新出现的 PENDING turn
    }

    private void drain(String convId, AtomicBoolean flag) {
        try {
            Consumer<String> processor = turnProcessor;
            if (processor == null) {
                logger.warn("No turn processor configured, skipping convId={}", convId);
                return;
            }
            while (true) {
                boolean hadWork = processOneIfPending(convId, processor);
                if (!hadWork) break;
            }
        } finally {
            flag.set(false);
            // recheck-after-release：避免 flag.set(false) 和下一次 submit 之间的窗口漏掉一个 turn
            Consumer<String> processor = turnProcessor;
            if (processor != null && flag.compareAndSet(false, true)) {
                try {
                    while (processOneIfPending(convId, processor)) { /* drain remaining */ }
                } finally {
                    flag.set(false);
                }
            }
        }
    }

    /** 返回 true 表示确实处理了一个 turn，调用方应继续尝试下一个；false 表示当前没有可处理的了。 */
    private boolean processOneIfPending(String convId, Consumer<String> processor) {
        try {
            return Boolean.TRUE.equals(processor.apply(convId) == null ? tryProcess(convId, processor) : null);
        } catch (RuntimeException e) {
            logger.error("Turn processor threw for convId={}: {}", convId, e.getMessage(), e);
            return false;
        }
    }

    private Boolean tryProcess(String convId, Consumer<String> processor) {
        processor.accept(convId);
        return true;
    }
}
```

等等——上面 `processOneIfPending` 写复杂了（`Consumer<String>` 天然没有返回值，无法表达"是否还有活干"）。改用更直接的接口：

- [ ] **Step 2 修正版**：把 `Consumer<String>` 换成自定义函数式接口 `TurnBatchProcessor`，语义是"处理该 convId 当前所有 PENDING turn，直到没有为止，内部自己 loop"。这样 dispatcher 不需要知道"一个 turn"是什么，只负责"同一 convId 不并发跑这个函数"。

```java
package com.yizhaoqi.roboknow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConversationTurnDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnDispatcher.class);

    @FunctionalInterface
    public interface TurnBatchProcessor {
        /** 处理该 convId 当前所有 PENDING turn，直到没有为止（内部自己 loop）。必须不抛出未捕获异常。 */
        void drainAllPending(String convId);
    }

    private final ConcurrentHashMap<String, AtomicBoolean> draining = new ConcurrentHashMap<>();
    private final Executor executor;
    private volatile TurnBatchProcessor processor;

    public ConversationTurnDispatcher(@Qualifier("turnWorkerExecutor") Executor executor) {
        this.executor = executor;
    }

    public void setProcessor(TurnBatchProcessor processor) {
        this.processor = processor;
    }

    /**
     * 唤醒该 convId 的处理。若已有 drain 在跑，直接返回——那个 drain 循环负责把新出现的
     * PENDING turn 一并处理掉（processor.drainAllPending 内部要在“看起来没活干”后再确认一次）。
     */
    public void submit(String convId) {
        AtomicBoolean flag = draining.computeIfAbsent(convId, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    TurnBatchProcessor p = processor;
                    if (p == null) {
                        logger.warn("No processor configured, skipping convId={}", convId);
                        return;
                    }
                    p.drainAllPending(convId);
                } finally {
                    flag.set(false);
                }
            });
        }
    }

    /** 仅测试可见：查询某 convId 当前是否有 drain 在跑。 */
    boolean isDraining(String convId) {
        AtomicBoolean flag = draining.get(convId);
        return flag != null && flag.get();
    }
}
```

（`drainAllPending` 内部必须自己 loop 到"确认没有 PENDING 了"才返回——这个 loop-until-empty 的职责放在 Task 7 的实现里，dispatcher 只负责"同一 convId 不重入"。）

- [ ] **Step 3: 测试**——核心断言：并发 submit 同一 convId 时,同一时刻只有一个 `drainAllPending` 在执行

```java
package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnDispatcherTest {

    @Test
    void neverRunsTwoDrainsForSameConvIdConcurrently() throws Exception {
        var dispatcher = new ConversationTurnDispatcher(new SimpleAsyncTaskExecutor());
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger totalRuns = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(1);

        dispatcher.setProcessor(convId -> {
            int c = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(m -> Math.max(m, c));
            try {
                Thread.sleep(100); // simulate work, wide enough window for a race to show up
                totalRuns.incrementAndGet();
            } catch (InterruptedException ignored) {
            } finally {
                concurrentCount.decrementAndGet();
                if (totalRuns.get() >= 1) done.countDown();
            }
        });

        // fire 10 concurrent submits for the SAME convId
        for (int i = 0; i < 10; i++) {
            dispatcher.submit("conv-race");
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(200); // let any stray second run surface
        assertEquals(1, maxConcurrent.get(), "must never run two drains for the same convId concurrently");
    }

    @Test
    void differentConvIdsRunInParallel() throws Exception {
        var dispatcher = new ConversationTurnDispatcher(new SimpleAsyncTaskExecutor());
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicBoolean sawOverlap = new AtomicBoolean(false);

        dispatcher.setProcessor(convId -> {
            bothStarted.countDown();
            try {
                boolean overlapped = bothStarted.await(2, TimeUnit.SECONDS);
                if (overlapped) sawOverlap.set(true);
            } catch (InterruptedException ignored) {}
        });

        dispatcher.submit("conv-a");
        dispatcher.submit("conv-b");

        Thread.sleep(500);
        assertTrue(sawOverlap.get(), "different convIds must be able to run concurrently");
    }
}
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ConversationTurnDispatcherTest`
Expected: 2/2 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/service/ConversationTurnDispatcher.java src/main/java/com/yizhaoqi/roboknow/config/AsyncConfig.java src/test/java/com/yizhaoqi/roboknow/service/ConversationTurnDispatcherTest.java
git commit -m "feat(conversation): in-process per-convId serial turn dispatcher"
```

---

### Task 7: `ReactAgentService` 改为按 turn 处理 + 先提交后推送

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/agent/ReactAgentService.java`
- Create: `src/main/java/com/yizhaoqi/roboknow/service/ConversationTurnCompletionService.java`
- Modify: `src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java`(`record` 拆出一个不做 DB 写入的 `recordToRedisOnly` 给新流程复用,DB 写入交给 `ConversationTurnCompletionService`)
- Test: `src/test/java/com/yizhaoqi/roboknow/service/ConversationTurnCompletionServiceIT.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/agent/ReactAgentServiceTurnTest.java`

**Consumes:** `ConversationTurnRepository.completeIfOwned/failIfOwned`(Task 2)、`ConversationTurnDispatcher.TurnBatchProcessor`(Task 6)、`WebSocketSessionRegistry`(Task 1)。

**Interfaces:**
- Produces: `ReactAgentService.processTurn(String userId, String convId, int turnSeq, String requestId, String attemptToken, String userMessage, WebSocketSession session)` — dispatcher 循环里对每个 claim 到的 PENDING turn 调用它。

- [ ] **Step 1: `ConversationTurnCompletionService`**——单事务:turn COMPLETE(条件更新,校验 attemptToken)+ assistant 消息插入(seq=turnSeq*2+1)。失败路径单独一个 `markFailed` 方法。

```java
package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * turn 完成/失败的唯一落库入口。“先提交、后推送”里的“提交”就是这个类。
 * completeIfOwned 用 attemptToken 做条件更新——过期 worker（比如重启后残留的旧线程）
 * 即使还在跑也提交不了，防止重复写入。
 */
@Service
public class ConversationTurnCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnCompletionService.class);

    private final ConversationTurnRepository turnRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationTurnCompletionService(ConversationTurnRepository turnRepository,
                                              ConversationMessageRepository messageRepository) {
        this.turnRepository = turnRepository;
        this.messageRepository = messageRepository;
    }

    /** @return true 表示本次提交成功拿到了权属；false 表示 attemptToken 已过期，调用方不应再推送结果给用户。 */
    @Transactional
    public boolean complete(Long turnId, String attemptToken, String convId, int turnSeq, String finalAnswer) {
        int updated = turnRepository.completeIfOwned(turnId, attemptToken, finalAnswer);
        if (updated == 0) {
            logger.warn("completeIfOwned no-op (stale attemptToken?) turnId={} convId={} turnSeq={}",
                    turnId, convId, turnSeq);
            return false;
        }
        ConversationMessage assistantRow = new ConversationMessage();
        assistantRow.setConvId(convId);
        assistantRow.setSeq(turnSeq * 2 + 1);
        assistantRow.setRole("assistant");
        assistantRow.setContent(finalAnswer);
        assistantRow.setCreatedAt(LocalDateTime.now());
        messageRepository.save(assistantRow);
        return true;
    }

    @Transactional
    public void markFailed(Long turnId, String attemptToken, String errorCode) {
        int updated = turnRepository.failIfOwned(turnId, attemptToken, errorCode);
        if (updated == 0) {
            logger.warn("failIfOwned no-op (stale attemptToken?) turnId={}", turnId);
        }
    }
}
```

- [ ] **Step 2: `MemoryManager` 拆分**——新增一个只做 Redis STM append + 压缩触发 + LTM 抽取的方法,不做 DB 写(DB 已经由 `ConversationTurnCompletionService` 处理),供新流程调用;老的 `record()` 保留给尚未迁移的调用方(如果有测试直接测 `record`,不动它)。

在 `MemoryManager.java` 里新增:

```java
    /**
     * turn 化流程专用：MySQL 落库已经由 ConversationTurnCompletionService 完成，
     * 这里只负责 Redis STM 热缓存 append + 压缩触发 + LTM 增量抽取，不重复写 DB。
     */
    public void syncRedisAfterTurnComplete(String userId, String convId, String question, String answer) {
        List<Map<String, String>> evicted =
                conversationMemory.appendAndEvictIfNeeded(convId, question, answer);

        if (!evicted.isEmpty()) {
            String existingSummary = conversationMemory.loadSummary(convId);
            contextCompressor.compressAsync(convId, existingSummary);
        }

        sessionRepository.findById(convId).ifPresent(session -> {
            session.setRoundCount(session.getRoundCount() + 1);
            if (session.getRoundCount() % extractEveryNRounds == 0) {
                contextCompressor.extractFactsAsync(userId, convId);
            }
            sessionRepository.save(session);
        });
    }
```

- [ ] **Step 3: `ReactAgentService` 新增 `processTurn`**,把原 `processMessage` 的方法体拆分:ReAct 循环(THINKING/ACTING/OBSERVING 事件照旧实时推送)算出 `finalAnswer` 后,不再直接 `streamText`+`sendCompletionNotification`,改为:

```java
    /**
     * 处理已经被 ConversationCommandService 落库为 PENDING、并被 dispatcher claim 为
     * PROCESSING 的一个 turn。ReAct 循环里的 THINKING/ACTING/OBSERVING 事件照常实时推送
     * （那是打字机效果之前的过程性 UI，不涉及“回答是否已经落库”）。
     * 关键变化：finalAnswer 算出来之后，先同步提交 MySQL（turn COMPLETE + assistant 消息），
     * 提交成功才做打字机效果推送和 completion 通知——这样即使推送过程中连接断开，
     * 回答本身已经落库，客户端重连后能从历史里读到，不会丢失也不会重复生成。
     */
    public void processTurn(String userId, String convId, int turnSeq, String requestId,
                             Long turnId, String attemptToken, String userMessage,
                             WebSocketSession session) {
        logger.info("ReactAgent processing turn, user: {}, convId: {}, turnSeq: {}", userId, convId, turnSeq);
        try {
            List<Map<String, String>> contextMessages =
                    memoryManager.loadContext(userId, convId, userMessage);

            AgentContext ctx = new AgentContext(userId, userMessage, convId,
                    new ArrayList<>(), session);

            String finalAnswer = runReActLoop(ctx, contextMessages);

            boolean committed = turnCompletionService.complete(turnId, attemptToken, convId, turnSeq, finalAnswer);
            if (!committed) {
                logger.warn("Turn commit skipped (stale attemptToken), not pushing result. convId={} turnSeq={}",
                        convId, turnSeq);
                return;
            }

            memoryManager.syncRedisAfterTurnComplete(userId, convId, userMessage, finalAnswer);

            streamText(ctx.getSession(), finalAnswer);
            sendCompletionNotification(ctx.getSession(), requestId, turnSeq);

            sessionRepository.findById(convId).ifPresent(s -> {
                if ("New conversation".equals(s.getTitle())) {
                    sessionManager.generateTitleAsync(convId, userMessage);
                }
            });
            logger.info("ReactAgent turn done, user: {}, convId: {}, turnSeq: {}", userId, convId, turnSeq);
        } catch (Exception e) {
            logger.error("ReactAgent turn failed: {}", e.getMessage(), e);
            turnCompletionService.markFailed(turnId, attemptToken, e.getClass().getSimpleName());
            sendError(session, "The AI service is temporarily unavailable. Please try again later.");
        } finally {
            agentStopService.clear(session.getId());
        }
    }
```

保留旧的 `processMessage(...)` 方法体不动是不行的——它和 `processTurn` 会重复持久化。**直接把 `processMessage` 删除**，`runReActLoop`/`buildInitialMessages`/`streamText`/`sendError` 等私有方法保留复用。`sendCompletionNotification` 签名改为带 `requestId`/`turnSeq`：

```java
    private void sendCompletionNotification(WebSocketSession session, String requestId, int turnSeq) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "completion");
            notification.put("status", "finished");
            notification.put("message", "Response completed");
            notification.put("requestId", requestId);
            notification.put("turnSeq", turnSeq);
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("date", java.time.LocalDateTime.now().toString());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("Failed to send completion notification: {}", e.getMessage(), e);
        }
    }
```

构造函数新增 `ConversationTurnCompletionService turnCompletionService` 依赖注入。

- [ ] **Step 4: `ConversationTurnCompletionServiceIT` 测试**

```java
package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ConversationTurnCompletionServiceIT {

    @Autowired private ConversationTurnCompletionService completionService;
    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationMessageRepository messageRepository;
    @Autowired private ConversationSessionRepository sessionRepository;

    private String convId;

    @BeforeEach
    void setUp() {
        convId = "it-complete-conv-" + UUID.randomUUID();
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);
    }

    @Test
    void completeWritesAssistantMessageAndMarksTurnComplete() {
        TurnAccepted accepted = commandService.acceptMessage("alice", convId, "req-1", "hi");
        ConversationTurn turn = turnRepository.findByConvIdAndRequestId(convId, "req-1").orElseThrow();
        String attemptToken = UUID.randomUUID().toString();
        turnRepository.claimForProcessing(turn.getId(), attemptToken);

        boolean ok = completionService.complete(turn.getId(), attemptToken, convId, accepted.turnSeq(), "hi back");
        assertTrue(ok);

        ConversationTurn reloaded = turnRepository.findById(turn.getId()).orElseThrow();
        assertEquals(ConversationTurn.Status.COMPLETE, reloaded.getStatus());
        assertEquals("hi back", reloaded.getAssistantContent());

        var messages = messageRepository.findByConvIdOrderBySeqAsc(convId);
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals(accepted.turnSeq() * 2, messages.get(0).getSeq());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals(accepted.turnSeq() * 2 + 1, messages.get(1).getSeq());
    }

    @Test
    void completeWithStaleAttemptTokenIsNoOp() {
        TurnAccepted accepted = commandService.acceptMessage("alice", convId, "req-2", "hi");
        ConversationTurn turn = turnRepository.findByConvIdAndRequestId(convId, "req-2").orElseThrow();
        turnRepository.claimForProcessing(turn.getId(), "real-token");

        boolean ok = completionService.complete(turn.getId(), "stale-token", convId, accepted.turnSeq(), "should not land");
        assertFalse(ok);

        var messages = messageRepository.findByConvIdOrderBySeqAsc(convId);
        assertEquals(1, messages.size(), "only the user message should exist, assistant write must be rejected");
    }
}
```

- [ ] **Step 5: 跑测试**

Run: `mvn -q test -Dtest=ConversationTurnCompletionServiceIT,ConversationTurnDispatcherTest`
Expected: 全部 PASS

- [ ] **Step 6: 编译整体检查**(`ReactAgentService` 改动面广,先确认能编译过,单测放到 Task 9 统一跑)

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/agent/ReactAgentService.java src/main/java/com/yizhaoqi/roboknow/service/ConversationTurnCompletionService.java src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java src/test/java/com/yizhaoqi/roboknow/service/ConversationTurnCompletionServiceIT.java
git commit -m "feat(conversation): commit-then-push turn completion, replacing fire-and-forget async persist"
```

---

### Task 8: `ChatWebSocketHandler` 接入新链路 + 删旧路径

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java`
- Modify: `src/main/java/com/yizhaoqi/roboknow/service/ChatHandler.java`(删 `processMessage`,只留 `stopResponse`)
- Delete: 无(保留 `ChatHandler` 类本身)
- Modify: `src/test/java/com/yizhaoqi/roboknow/service/ChatHandlerCoverageTest.java`(删掉测已删方法的用例)
- Create: `@PostConstruct` 装配 `ConversationTurnDispatcher` 处理器 —— 放在新建的 `src/main/java/com/yizhaoqi/roboknow/config/ConversationTurnWiring.java`

**Consumes:** `ConversationCommandService.acceptMessage`(Task 5)、`ConversationTurnDispatcher.submit/setProcessor`(Task 6)、`ReactAgentService.processTurn`(Task 7)、`WebSocketSessionRegistry`(Task 1)。

- [ ] **Step 1: `ConversationTurnWiring`**——用一个独立配置类把 dispatcher 的 processor 接到 `ReactAgentService.processTurn`,避免 `ConversationTurnDispatcher` 直接依赖 `ReactAgentService`(循环依赖风险:Agent 依赖 Memory,Memory 不该反过来依赖 Agent)。

```java
package com.yizhaoqi.roboknow.config;

import com.yizhaoqi.roboknow.agent.ReactAgentService;
import com.yizhaoqi.roboknow.handler.WebSocketSessionRegistry;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import com.yizhaoqi.roboknow.service.ConversationTurnCompletionService;
import com.yizhaoqi.roboknow.service.ConversationTurnDispatcher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 把 dispatcher 的“串行执行一批 turn”职责，和 ReactAgentService 的“怎么处理一个 turn”职责接起来。
 * 放在单独的 wiring 类，避免 ConversationTurnDispatcher（偏基础设施）反向依赖 ReactAgentService（偏业务）。
 */
@Component
public class ConversationTurnWiring {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnWiring.class);

    private final ConversationTurnDispatcher dispatcher;
    private final ConversationTurnRepository turnRepository;
    private final ConversationTurnCompletionService completionService;
    private final ReactAgentService reactAgentService;
    private final WebSocketSessionRegistry sessionRegistry;

    public ConversationTurnWiring(ConversationTurnDispatcher dispatcher,
                                   ConversationTurnRepository turnRepository,
                                   ConversationTurnCompletionService completionService,
                                   ReactAgentService reactAgentService,
                                   WebSocketSessionRegistry sessionRegistry) {
        this.dispatcher = dispatcher;
        this.turnRepository = turnRepository;
        this.completionService = completionService;
        this.reactAgentService = reactAgentService;
        this.sessionRegistry = sessionRegistry;
    }

    @PostConstruct
    void wire() {
        dispatcher.setProcessor(this::drainAllPending);
    }

    private void drainAllPending(String convId) {
        while (true) {
            Optional<ConversationTurn> next = turnRepository
                    .findFirstByConvIdAndStatusOrderByTurnSeqAsc(convId, ConversationTurn.Status.PENDING);
            if (next.isEmpty()) return;

            ConversationTurn turn = next.get();
            String attemptToken = UUID.randomUUID().toString();
            int claimed = turnRepository.claimForProcessing(turn.getId(), attemptToken);
            if (claimed == 0) {
                // 被别的（理论上不该存在的并发）drain 抢走了，跳过继续找下一个，防止死循环
                continue;
            }

            Optional<WebSocketSession> sessionOpt = sessionRegistry.get(turn.getConvId() == null
                    ? null : resolveUserId(turn));
            if (sessionOpt.isEmpty()) {
                logger.warn("No live WebSocket session to push turn result, marking FAILED. convId={} turnSeq={}",
                        convId, turn.getTurnSeq());
                completionService.markFailed(turn.getId(), attemptToken, "NO_LIVE_SESSION");
                continue;
            }

            try {
                reactAgentService.processTurn(resolveUserId(turn), convId, turn.getTurnSeq(),
                        turn.getRequestId(), turn.getId(), attemptToken, turn.getUserContent(),
                        sessionOpt.get());
            } catch (Exception e) {
                logger.error("Unhandled exception processing turn id={}: {}", turn.getId(), e.getMessage(), e);
                completionService.markFailed(turn.getId(), attemptToken, "UNHANDLED_EXCEPTION");
            }
        }
    }

    private String resolveUserId(ConversationTurn turn) {
        // ConversationTurn 目前没存 userId——turn 只认 convId。userId 用于查 WebSocketSessionRegistry
        // 和喂给 ReactAgentService 做权限/记忆检索。从 ConversationSession 查一次。
        return sessionRepositoryUserId(turn.getConvId());
    }
}
```

这里我漏了一步：`ConversationTurn` 没存 `userId`，需要反查 `ConversationSession.getUserId()`。修正——给 `ConversationTurnWiring` 加 `ConversationSessionRepository` 依赖，`resolveUserId` 改为：

```java
    private final com.yizhaoqi.roboknow.repository.ConversationSessionRepository sessionRepository;
    // 构造函数加上这个参数

    private String resolveUserId(ConversationTurn turn) {
        return sessionRepository.findById(turn.getConvId())
                .map(com.yizhaoqi.roboknow.model.ConversationSession::getUserId)
                .orElseThrow(() -> new IllegalStateException("Session missing for convId=" + turn.getConvId()));
    }
```

并删掉上面错误的 `sessionOpt` 那行里对 `resolveUserId(turn)` 判空 `turn.getConvId() == null ? null : ...` 的多余写法，直接：

```java
            String userId = resolveUserId(turn);
            Optional<WebSocketSession> sessionOpt = sessionRegistry.get(userId);
```

（这段是本计划里最容易在实现时出线头的地方——写代码时按"先查 session 拿 userId,再查 registry 拿连接"这个顺序重新捋一遍,不要照抄上面带错误的草稿。）

需要 import `org.springframework.web.socket.WebSocketSession`。

- [ ] **Step 2: `ChatWebSocketHandler.handleTextMessage`**——chat 消息分支改为:

```java
                    // Chat message with optional convId + requestId
                    String userMessage = (String) json.get("message");
                    if (userMessage != null && !userMessage.isBlank()) {
                        String convId = resolveConvId(userId, (String) json.get("convId"));
                        String requestId = (String) json.get("requestId");
                        if (requestId == null || requestId.isBlank()) {
                            requestId = java.util.UUID.randomUUID().toString();
                        }
                        TurnAccepted accepted = commandService.acceptMessage(userId, convId, requestId, userMessage);
                        sendAccepted(session, accepted, convId);
                        turnDispatcher.submit(convId);
                        return;
                    }
```

同样处理 plain-text 兼容分支(生成 requestId,走同一条 accept+submit 路径)。构造函数新增 `ConversationCommandService commandService`、`ConversationTurnDispatcher turnDispatcher` 依赖,`import com.yizhaoqi.roboknow.service.TurnAccepted;`。新增私有方法:

```java
    private void sendAccepted(WebSocketSession session, TurnAccepted accepted, String convId) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "accepted",
                    "convId", convId,
                    "requestId", accepted.requestId(),
                    "turnSeq", accepted.turnSeq(),
                    "status", accepted.status().name()
            ))));
        } catch (Exception e) {
            logger.error("Failed to send accepted ack: {}", e.getMessage(), e);
        }
    }
```

- [ ] **Step 3: `ChatHandler` 删 `processMessage`**,只留:

```java
package com.yizhaoqi.roboknow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final AgentStopService agentStopService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHandler(AgentStopService agentStopService) {
        this.agentStopService = agentStopService;
    }

    public void stopResponse(String userId, WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("收到停止请求，用户: {}，会话: {}", userId, sessionId);
        agentStopService.requestStop(sessionId);
        try {
            Map<String, Object> response = Map.of(
                "type", "stop",
                "message", "响应已停止",
                "timestamp", System.currentTimeMillis(),
                "date", java.time.Instant.now().toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception e) {
            logger.error("发送停止确认失败: {}", e.getMessage(), e);
        }
    }
}
```

`ChatWebSocketHandler` 的 `chatHandler.stopResponse(...)` 调用保持不变。

- [ ] **Step 4: 更新/删除过时测试**——`ChatHandlerCoverageTest.processMessageDelegatesToReactAgentAsynchronously` 和 `processMessageSwallowsReactAgentExceptionViaCompletableFuture` 两个用例测的是已删方法,删掉这两个 `@Test`,保留 `stopResponse` 相关两个;构造 `ChatHandler` 的地方去掉 `reactAgentService` 参数。同样检查 `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerTest.java`(旧包名遗留文件)是否还在编译——如果它引用了 `ChatHandler(ReactAgentService, AgentStopService)` 构造函数,一并删除这个过时文件(先用 Grep 确认它是否被其它测试依赖,预期没有)。

- [ ] **Step 5: 编译 + 全量单测**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

Run: `mvn -q test -Dtest=ChatHandlerCoverageTest,ConversationCommandServiceIT,ConversationTurnCompletionServiceIT,ConversationTurnDispatcherTest,ConversationTurnRepositoryIT,ConversationSessionTurnSeqIT,ConversationMessageUniqueSeqIT,WebSocketSessionRegistryTest`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java src/main/java/com/yizhaoqi/roboknow/service/ChatHandler.java src/main/java/com/yizhaoqi/roboknow/config/ConversationTurnWiring.java src/test/java/com/yizhaoqi/roboknow/service/ChatHandlerCoverageTest.java
git commit -m "feat(conversation): wire WebSocket handler to turn-based accept/dispatch pipeline"
```

---

### Task 9: 全量回归 + 真实并发验收测试

**Files:**
- Create: `src/test/java/com/yizhaoqi/roboknow/integration/ConversationTurnOrderingE2EIT.java`

**目标:** 用真实 Spring 上下文 + 真实 MySQL,模拟"同一 convId 连续到达 3 条消息、不等前一条处理完就发下一条"，验证：
1. 三次 `acceptMessage` 拿到 turnSeq = 1,2,3（不依赖 WebSocket，直接测 command+dispatcher+一个假的慢 processor，隔离掉真实 LLM 调用）。
2. `conversation_messages` 表最终顺序是 U1,A1,U2,A2,U3,A3（seq 0..5 连续）。
3. 第二条在第一条完成前到达时不会被并发处理（用一个人工加锁的 fake processor 验证同一时刻只有一个 turn 在"处理中"）。

- [ ] **Step 1: 写测试**——用 `ConversationTurnDispatcher` + 一个手写的、写 `ConversationMessage` 的 fake processor（不经过真实 `ReactAgentService`/LLM，隔离掉外部依赖，聚焦本阶段要证明的顺序/并发不变量）：

```java
package com.yizhaoqi.roboknow.integration;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import com.yizhaoqi.roboknow.service.ConversationCommandService;
import com.yizhaoqi.roboknow.service.ConversationTurnCompletionService;
import com.yizhaoqi.roboknow.service.ConversationTurnDispatcher;
import com.yizhaoqi.roboknow.service.TurnAccepted;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ConversationTurnOrderingE2EIT {

    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationTurnDispatcher dispatcher;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationTurnCompletionService completionService;
    @Autowired private ConversationMessageRepository messageRepository;
    @Autowired private ConversationSessionRepository sessionRepository;

    @Test
    void threeRapidMessagesToSameConvProduceStrictlyOrderedHistoryNoOverlap() throws Exception {
        String convId = "it-e2e-conv-" + UUID.randomUUID();
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);

        AtomicInteger concurrentProcessing = new AtomicInteger(0);
        AtomicInteger maxConcurrentProcessing = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(3);

        // fake processor：模拟“处理一个 turn 要花一点时间”，同时记录是否出现并发处理
        dispatcher.setProcessor(convId2 -> {
            while (true) {
                Optional<ConversationTurn> next = turnRepository.findFirstByConvIdAndStatusOrderByTurnSeqAsc(
                        convId2, ConversationTurn.Status.PENDING);
                if (next.isEmpty()) return;
                ConversationTurn turn = next.get();
                String attemptToken = UUID.randomUUID().toString();
                if (turnRepository.claimForProcessing(turn.getId(), attemptToken) == 0) continue;

                int c = concurrentProcessing.incrementAndGet();
                maxConcurrentProcessing.updateAndGet(m -> Math.max(m, c));
                try {
                    Thread.sleep(150); // simulate "slow LLM turn"
                    completionService.complete(turn.getId(), attemptToken, convId2, turn.getTurnSeq(),
                            "answer-to-" + turn.getUserContent());
                } catch (InterruptedException ignored) {
                } finally {
                    concurrentProcessing.decrementAndGet();
                    allDone.countDown();
                }
            }
        });

        // 三条消息几乎同时到达，不等前一条处理完
        TurnAccepted t1 = commandService.acceptMessage("alice", convId, "req-1", "first question");
        dispatcher.submit(convId);
        TurnAccepted t2 = commandService.acceptMessage("alice", convId, "req-2", "second question");
        dispatcher.submit(convId);
        TurnAccepted t3 = commandService.acceptMessage("alice", convId, "req-3", "third question");
        dispatcher.submit(convId);

        assertEquals(1, t1.turnSeq());
        assertEquals(2, t2.turnSeq());
        assertEquals(3, t3.turnSeq());

        assertTrue(allDone.await(10, TimeUnit.SECONDS), "all three turns must complete");
        assertEquals(1, maxConcurrentProcessing.get(), "turns for the same convId must never overlap");

        List<com.yizhaoqi.roboknow.model.ConversationMessage> history =
                messageRepository.findByConvIdOrderBySeqAsc(convId);
        assertEquals(6, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("first question", history.get(0).getContent());
        assertEquals("assistant", history.get(1).getRole());
        assertEquals("answer-to-first question", history.get(1).getContent());
        assertEquals("user", history.get(2).getRole());
        assertEquals("second question", history.get(2).getContent());
        assertEquals("assistant", history.get(3).getRole());
        assertEquals("user", history.get(4).getRole());
        assertEquals("third question", history.get(4).getContent());
        assertEquals("assistant", history.get(5).getRole());

        for (int i = 0; i < history.size(); i++) {
            assertEquals(i, history.get(i).getSeq(), "seq must be strictly 0..5 contiguous");
        }
    }
}
```

- [ ] **Step 2: 跑这个测试**

Run: `mvn -q test -Dtest=ConversationTurnOrderingE2EIT`
Expected: PASS。这是本阶段的验收核心——证明"三条消息不等前一条处理完就连续发送"不再产生乱序或并发覆盖。

- [ ] **Step 3: 跑全量测试套件确认没有破坏其它东西**

Run: `mvn -q test`
Expected: BUILD SUCCESS，0 失败（预期会跑很久，覆盖全项目)

- [ ] **Step 4: 真实进程 + WebSocket 手动验收**（不只信单测——按 CLAUDE.md 要求实际把功能跑起来看)

启动后端（复用现有注入 key 的方式）：
```bash
cd "c:\Users\Siyuan\Documents\Henry\RoboKnow"
OPENAI_API_KEY=$(sed -n 's/.*key: \(sk-proj-[^ ]*\).*/\1/p' src/main/resources/application-docker.yml | head -1) mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

用一个小脚本对同一个 convId 连续发 3 条消息（不等回答），确认：
- 收到 3 条 `type:accepted`，`turnSeq` 分别是 1/2/3；
- 最终 3 条 `type:completion`，`turnSeq` 和上面一一对应；
- 查 `conversation_messages` 表该 convId 的记录，`seq` 严格 0..5，内容顺序对应 U1,A1,U2,A2,U3,A3。

```bash
docker exec mysql mysql -uroot -pRoboKnow2025 RoboKnow -N -e "SELECT seq, role, LEFT(content,40) FROM conversation_messages WHERE conv_id='<刚才用的 convId>' ORDER BY seq"
```

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/yizhaoqi/roboknow/integration/ConversationTurnOrderingE2EIT.java
git commit -m "test(conversation): end-to-end concurrency test proving strict per-conv turn ordering"
```

---

## 验收标准（对应原方案 §15.1 的阶段一子集）

- [ ] 同一 convId 连续发送 3 条消息，turnSeq 唯一且严格递增，`conversation_messages` 最终顺序是 U1,A1,U2,A2,U3,A3，seq 无空洞无重复。
- [ ] 同一 requestId 重复提交只产生一个 turn，不重复插入消息。
- [ ] 不同 convId 之间处理并行，不被全局锁串行化（`ConversationTurnDispatcherTest.differentConvIdsRunInParallel`）。
- [ ] 回答内容先于 completion 通知落库（`ConversationTurnCompletionServiceIT` + `processTurn` 代码路径审查）。
- [ ] 全量 `mvn test` 通过。
- [ ] 真实进程手动验证一次（不只信单测）。
