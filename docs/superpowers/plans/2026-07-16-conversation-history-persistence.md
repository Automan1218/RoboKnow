# Conversation History Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop chat history from disappearing when its Redis key hits the 7-day TTL, by adding a MySQL durable layer that Redis reads/writes through as a cache-aside hot path.

**Architecture:** New `conversation_messages` table stores every user/assistant message pair. `MemoryManager.record()` keeps writing to Redis synchronously (unchanged) and additionally fires an `@Async("memoryExecutor")` write to MySQL. `ConversationMemory.loadHistory()` becomes cache-aside: Redis hit returns immediately (unchanged hot path), Redis miss reads MySQL and repopulates Redis. `ConversationController` and `AdminController` stop hitting `redisTemplate` directly and instead go through `ConversationMemory.loadHistory()` / `SessionManager.listSessions()`.

**Tech Stack:** Spring Boot 3, Spring Data JPA (MySQL, `ddl-auto: update`, no migration framework), Spring Data Redis (`StringRedisTemplate`), JUnit 5 + Mockito, existing `@Async("memoryExecutor")` thread pool (`AsyncConfig`).

## Global Constraints

- No foreign key from `conversation_messages.conv_id` to `conversation_sessions.id` — matches existing schema style, avoids `ddl-auto` migration friction.
- Async write failures: log ERROR only, no retry, no dead-letter queue.
- Redis backfill failures: swallow exception, return DB result, let the next request self-heal the cache.
- Do not touch `ContextCompressor` (STM summary) or `LongTermMemory`/`UserMemoryFact` (fact extraction) — out of scope.
- Do not migrate the 9 rows in the legacy `conversations` table — drop it.
- Field naming in `conversation_messages` mirrors the Redis history JSON shape (`role`, `content`, timestamp) so serialization stays a straight map, no transformation layer.

---

### Task 1: `ConversationMessage` entity + repository

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/model/ConversationMessage.java`
- Create: `src/main/java/com/yizhaoqi/roboknow/repository/ConversationMessageRepository.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/repository/ConversationMessageRepositoryTest.java`

**Interfaces:**
- Produces: `ConversationMessage` (fields: `id: Long`, `convId: String`, `seq: int`, `role: String`, `content: String`, `createdAt: LocalDateTime`), `ConversationMessageRepository.findByConvIdOrderBySeqAsc(String convId): List<ConversationMessage>`, `ConversationMessageRepository.countByConvId(String convId): long`.

- [ ] **Step 1: Write the entity**

```java
package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_messages", indexes = {
        @Index(name = "idx_conv_seq", columnList = "conv_id,seq")
})
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conv_id", nullable = false, length = 36)
    private String convId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: Write the repository**

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findByConvIdOrderBySeqAsc(String convId);

    long countByConvId(String convId);
}
```

- [ ] **Step 3: Write the repository test**

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class ConversationMessageRepositoryTest {

    @Autowired
    private ConversationMessageRepository repository;

    private ConversationMessage build(String convId, int seq, String role, String content) {
        ConversationMessage m = new ConversationMessage();
        m.setConvId(convId);
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    @Test
    void findByConvIdOrderBySeqAscReturnsInOrder() {
        repository.save(build("conv-1", 1, "assistant", "hi back"));
        repository.save(build("conv-1", 0, "user", "hi"));
        repository.save(build("conv-2", 0, "user", "other conv"));

        List<ConversationMessage> result = repository.findByConvIdOrderBySeqAsc("conv-1");

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).getSeq());
        assertEquals("user", result.get(0).getRole());
        assertEquals(1, result.get(1).getSeq());
    }

    @Test
    void countByConvIdCountsOnlyMatchingConversation() {
        repository.save(build("conv-1", 0, "user", "hi"));
        repository.save(build("conv-1", 1, "assistant", "hi back"));
        repository.save(build("conv-2", 0, "user", "other"));

        assertEquals(2, repository.countByConvId("conv-1"));
        assertEquals(1, repository.countByConvId("conv-2"));
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn test -Dtest=ConversationMessageRepositoryTest`
Expected: PASS (2 tests). `@DataJpaTest` spins up an embedded/test DB and auto-creates the table from the entity — first run also proves the entity mapping is valid.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/model/ConversationMessage.java src/main/java/com/yizhaoqi/roboknow/repository/ConversationMessageRepository.java src/test/java/com/yizhaoqi/roboknow/repository/ConversationMessageRepositoryTest.java
git commit -m "feat(memory): add ConversationMessage entity and repository"
```

---

### Task 2: `MessagePersistenceService`

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/MessagePersistenceService.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/memory/MessagePersistenceServiceTest.java`

**Interfaces:**
- Consumes: `ConversationMessageRepository` (Task 1).
- Produces: `MessagePersistenceService.saveAsync(String convId, String question, String answer): void` (fire-and-forget, `@Async`), `MessagePersistenceService.loadFromDb(String convId): List<Map<String,String>>` (each map has keys `role`, `content`, `timestamp`, timestamp formatted `yyyy-MM-dd'T'HH:mm:ss` — same format `ConversationMemory` already uses).

- [ ] **Step 1: Write the failing tests**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagePersistenceServiceTest {

    private ConversationMessageRepository repository;
    private MessagePersistenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationMessageRepository.class);
        service = new MessagePersistenceService(repository);
    }

    @Test
    void saveAsyncPersistsUserThenAssistantWithIncrementingSeq() {
        when(repository.countByConvId("conv-1")).thenReturn(4L);

        service.saveAsync("conv-1", "question", "answer");

        ArgumentCaptor<ConversationMessage> captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(repository, times(2)).save(captor.capture());

        List<ConversationMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("question", saved.get(0).getContent());
        assertEquals(4, saved.get(0).getSeq());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("answer", saved.get(1).getContent());
        assertEquals(5, saved.get(1).getSeq());
        assertEquals("conv-1", saved.get(0).getConvId());
    }

    @Test
    void saveAsyncSwallowsRepositoryExceptions() {
        when(repository.countByConvId(anyString())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.saveAsync("conv-1", "q", "a"));
    }

    @Test
    void loadFromDbReturnsEmptyListWhenNoRows() {
        when(repository.findByConvIdOrderBySeqAsc("conv-1")).thenReturn(List.of());

        List<Map<String, String>> result = service.loadFromDb("conv-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void loadFromDbMapsRowsToRoleContentTimestamp() {
        ConversationMessage m = new ConversationMessage();
        m.setRole("user");
        m.setContent("hello");
        m.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 30, 0));
        when(repository.findByConvIdOrderBySeqAsc("conv-1")).thenReturn(List.of(m));

        List<Map<String, String>> result = service.loadFromDb("conv-1");

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).get("role"));
        assertEquals("hello", result.get(0).get("content"));
        assertEquals("2026-07-01T10:30:00", result.get(0).get("timestamp"));
    }

    @Test
    void loadFromDbSwallowsRepositoryExceptionsAndReturnsEmpty() {
        when(repository.findByConvIdOrderBySeqAsc(anyString())).thenThrow(new RuntimeException("db down"));

        List<Map<String, String>> result = service.loadFromDb("conv-1");

        assertTrue(result.isEmpty());
    }
}
```

Add the missing import at the top of the test: `import org.mockito.ArgumentCaptor;`

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=MessagePersistenceServiceTest`
Expected: FAIL — `MessagePersistenceService` does not exist yet (compile error).

- [ ] **Step 3: Write the implementation**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MessagePersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(MessagePersistenceService.class);
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ConversationMessageRepository repository;

    public MessagePersistenceService(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    /** Durable write-behind: persists the user+assistant pair. Never blocks the caller. */
    @Async("memoryExecutor")
    public void saveAsync(String convId, String question, String answer) {
        try {
            int nextSeq = (int) repository.countByConvId(convId);
            LocalDateTime now = LocalDateTime.now();

            ConversationMessage userMsg = new ConversationMessage();
            userMsg.setConvId(convId);
            userMsg.setSeq(nextSeq);
            userMsg.setRole("user");
            userMsg.setContent(question);
            userMsg.setCreatedAt(now);
            repository.save(userMsg);

            ConversationMessage assistantMsg = new ConversationMessage();
            assistantMsg.setConvId(convId);
            assistantMsg.setSeq(nextSeq + 1);
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(answer);
            assistantMsg.setCreatedAt(now);
            repository.save(assistantMsg);
        } catch (Exception e) {
            logger.error("Failed to persist message pair for convId={}: {}", convId, e.getMessage());
        }
    }

    /** Cache-aside DB read. Returns empty list on miss or failure — never throws. */
    public List<Map<String, String>> loadFromDb(String convId) {
        try {
            List<ConversationMessage> rows = repository.findByConvIdOrderBySeqAsc(convId);
            List<Map<String, String>> result = new ArrayList<>();
            for (ConversationMessage row : rows) {
                Map<String, String> m = new HashMap<>();
                m.put("role", row.getRole());
                m.put("content", row.getContent());
                m.put("timestamp", row.getCreatedAt().format(TS_FMT));
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to load messages from DB for convId={}: {}", convId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=MessagePersistenceServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/MessagePersistenceService.java src/test/java/com/yizhaoqi/roboknow/memory/MessagePersistenceServiceTest.java
git commit -m "feat(memory): add MessagePersistenceService for durable message writes"
```

---

### Task 3: Wire `MessagePersistenceService` into `MemoryManager.record()`

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/memory/MemoryManagerTest.java` (new file — no existing test for this class)

**Interfaces:**
- Consumes: `MessagePersistenceService.saveAsync(String, String, String)` (Task 2).

- [ ] **Step 1: Write the failing test**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MemoryManagerTest {

    private ConversationMemory conversationMemory;
    private LongTermMemory longTermMemory;
    private MemoryRetriever memoryRetriever;
    private ContextCompressor contextCompressor;
    private ConversationSessionRepository sessionRepository;
    private MessagePersistenceService messagePersistenceService;
    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        conversationMemory = mock(ConversationMemory.class);
        longTermMemory = mock(LongTermMemory.class);
        memoryRetriever = mock(MemoryRetriever.class);
        contextCompressor = mock(ContextCompressor.class);
        sessionRepository = mock(ConversationSessionRepository.class);
        messagePersistenceService = mock(MessagePersistenceService.class);
        manager = new MemoryManager(conversationMemory, longTermMemory, memoryRetriever,
                contextCompressor, sessionRepository, messagePersistenceService);

        when(conversationMemory.appendAndEvictIfNeeded(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(sessionRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void recordTriggersDurableWrite() {
        manager.record("alice", "conv-1", "question", "answer");

        verify(messagePersistenceService).saveAsync("conv-1", "question", "answer");
    }

    @Test
    void recordStillAppendsToRedisFirst() {
        manager.record("alice", "conv-1", "question", "answer");

        verify(conversationMemory).appendAndEvictIfNeeded("conv-1", "question", "answer");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MemoryManagerTest`
Expected: FAIL — no `MemoryManager` constructor takes 6 args yet.

- [ ] **Step 3: Update `MemoryManager`**

In `src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java`, add the field, constructor parameter, and call. Replace the existing constructor and field block (lines 20-42) with:

```java
    private final ConversationMemory conversationMemory;
    private final LongTermMemory longTermMemory;
    private final MemoryRetriever memoryRetriever;
    private final ContextCompressor contextCompressor;
    private final ConversationSessionRepository sessionRepository;
    private final MessagePersistenceService messagePersistenceService;

    @Value("${memory.context-window:10}")
    private int contextWindow;

    @Value("${memory.extract-every-n-rounds:10}")
    private int extractEveryNRounds;

    public MemoryManager(ConversationMemory conversationMemory,
                          LongTermMemory longTermMemory,
                          MemoryRetriever memoryRetriever,
                          ContextCompressor contextCompressor,
                          ConversationSessionRepository sessionRepository,
                          MessagePersistenceService messagePersistenceService) {
        this.conversationMemory = conversationMemory;
        this.longTermMemory = longTermMemory;
        this.memoryRetriever = memoryRetriever;
        this.contextCompressor = contextCompressor;
        this.sessionRepository = sessionRepository;
        this.messagePersistenceService = messagePersistenceService;
    }
```

Then in `record()` (currently lines 81-101), add the durable write right after the Redis append. The method becomes:

```java
    @Transactional
    public void record(String userId, String convId, String question, String answer) {
        List<Map<String, String>> evicted =
                conversationMemory.appendAndEvictIfNeeded(convId, question, answer);

        messagePersistenceService.saveAsync(convId, question, answer);

        if (!evicted.isEmpty()) {
            String existingSummary = conversationMemory.loadSummary(convId);
            contextCompressor.compressAsync(convId, existingSummary);
        }

        // Incremental fact extraction every N rounds
        sessionRepository.findById(convId).ifPresent(session -> {
            session.setRoundCount(session.getRoundCount() + 1);
            if (session.getRoundCount() % extractEveryNRounds == 0) {
                logger.debug("Incremental fact extraction at round={} for convId={}",
                        session.getRoundCount(), convId);
                contextCompressor.extractFactsAsync(userId, convId);
            }
            sessionRepository.save(session);
        });
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=MemoryManagerTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java src/test/java/com/yizhaoqi/roboknow/memory/MemoryManagerTest.java
git commit -m "feat(memory): wire durable message write into MemoryManager.record"
```

---

### Task 4: Cache-aside read in `ConversationMemory.loadHistory()`

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/memory/ConversationMemory.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/memory/ConversationMemoryTest.java` (new file — no existing test for this class)

**Interfaces:**
- Consumes: `MessagePersistenceService.loadFromDb(String convId): List<Map<String,String>>` (Task 2).
- Produces: `ConversationMemory.loadHistory(String convId)` now backfills Redis on miss (same signature, callers unchanged).

- [ ] **Step 1: Write the failing test**

```java
package com.yizhaoqi.roboknow.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationMemoryTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private TokenBudget tokenBudget;
    private MessagePersistenceService messagePersistenceService;
    private ConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        tokenBudget = mock(TokenBudget.class);
        messagePersistenceService = mock(MessagePersistenceService.class);
        memory = new ConversationMemory(redis, tokenBudget,
                new com.fasterxml.jackson.databind.ObjectMapper(), messagePersistenceService);
    }

    @Test
    void loadHistoryReturnsRedisDataWithoutTouchingDb() {
        when(ops.get("conversation:conv-1"))
                .thenReturn("[{\"role\":\"user\",\"content\":\"hi\",\"timestamp\":\"2026-07-01T10:00:00\"}]");

        List<Map<String, String>> result = memory.loadHistory("conv-1");

        assertEquals(1, result.size());
        verifyNoInteractions(messagePersistenceService);
    }

    @Test
    void loadHistoryFallsBackToDbOnRedisMissAndBackfillsRedis() {
        when(ops.get("conversation:conv-1")).thenReturn(null);
        List<Map<String, String>> dbHistory = List.of(
                Map.of("role", "user", "content", "hi", "timestamp", "2026-07-01T10:00:00"));
        when(messagePersistenceService.loadFromDb("conv-1")).thenReturn(dbHistory);

        List<Map<String, String>> result = memory.loadHistory("conv-1");

        assertEquals(1, result.size());
        assertEquals("hi", result.get(0).get("content"));
        verify(ops).set(eq("conversation:conv-1"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void loadHistoryReturnsEmptyWhenBothRedisAndDbMiss() {
        when(ops.get("conversation:conv-1")).thenReturn(null);
        when(messagePersistenceService.loadFromDb("conv-1")).thenReturn(List.of());

        List<Map<String, String>> result = memory.loadHistory("conv-1");

        assertTrue(result.isEmpty());
        verify(ops, never()).set(eq("conversation:conv-1"), anyString(), any(java.time.Duration.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ConversationMemoryTest`
Expected: FAIL — `ConversationMemory` constructor does not take a `MessagePersistenceService` yet.

- [ ] **Step 3: Update `ConversationMemory`**

Add the field and constructor parameter (replace lines 29-42):

```java
    private final StringRedisTemplate redis;
    private final TokenBudget tokenBudget;
    private final ObjectMapper objectMapper;
    private final MessagePersistenceService messagePersistenceService;

    @Value("${memory.compress-threshold:0.80}")
    private double compressThreshold;

    public ConversationMemory(StringRedisTemplate redis,
                               TokenBudget tokenBudget,
                               ObjectMapper objectMapper,
                               MessagePersistenceService messagePersistenceService) {
        this.redis = redis;
        this.tokenBudget = tokenBudget;
        this.objectMapper = objectMapper;
        this.messagePersistenceService = messagePersistenceService;
    }
```

Replace `loadHistory()` (lines 44-54) with:

```java
    /** Load full history for a conversation. Redis hit returns immediately; miss falls back to MySQL and backfills Redis. */
    public List<Map<String, String>> loadHistory(String convId) {
        String json = redis.opsForValue().get(historyKey(convId));
        if (json != null) {
            try {
                return objectMapper.readValue(json, new TypeReference<>() {});
            } catch (Exception e) {
                logger.error("Failed to parse conversation history convId={}: {}", convId, e.getMessage());
                return new ArrayList<>();
            }
        }

        List<Map<String, String>> dbHistory = messagePersistenceService.loadFromDb(convId);
        if (!dbHistory.isEmpty()) {
            saveHistory(convId, dbHistory);
        }
        return dbHistory;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=ConversationMemoryTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Fix call sites broken by the constructor change**

`SessionManager` and any other Spring-managed bean only inject `ConversationMemory` via the Spring container (autowired by type), so no other source file constructs `new ConversationMemory(...)` directly — confirm with:

Run: `grep -rn "new ConversationMemory(" src/`
Expected: no matches outside `ConversationMemoryTest.java`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/ConversationMemory.java src/test/java/com/yizhaoqi/roboknow/memory/ConversationMemoryTest.java
git commit -m "feat(memory): cache-aside DB fallback in ConversationMemory.loadHistory"
```

---

### Task 5: Collapse `ConversationController` onto `ConversationMemory.loadHistory()`

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/controller/ConversationController.java`
- Modify: `src/test/java/com/yizhaoqi/roboknow/controller/ConversationControllerTest.java`

**Interfaces:**
- Consumes: `ConversationMemory.loadHistory(String convId): List<Map<String,String>>` (Task 4).

- [ ] **Step 1: Update the failing tests first**

The existing tests stub `ops.get("conversation:conv-abc")` directly (bypassing `ConversationMemory`). Replace the Redis-history-specific tests in `ConversationControllerTest.java` to stub `ConversationMemory` instead. Replace the whole file's Redis-history section (`getConversationsReturnsMessagesFromRedisActiveKey` through `getConversationsReturnsEmptyWhenRedisDataIsNull`, lines 93-151) with:

```java
    @Test
    void getConversationsReturnsMessagesFromConversationMemory() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn("conv-abc");
        when(conversationMemory.loadHistory("conv-abc")).thenReturn(
                List.of(Map.of("role", "user", "content", "hello", "timestamp", "2026-06-11T10:00:00")));

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
        List<?> data = (List<?>) extractData(r);
        assertEquals(1, data.size());
    }

    @Test
    void getConversationsFiltersMessagesByDateRange() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn("conv-abc");
        when(conversationMemory.loadHistory("conv-abc")).thenReturn(List.of(
                Map.of("role", "user", "content", "old", "timestamp", "2026-01-01T10:00:00"),
                Map.of("role", "user", "content", "new", "timestamp", "2026-06-11T10:00:00")));

        ResponseEntity<?> r = controller.getConversations(
                "Bearer valid", "2026-06-01", "2026-06-30");
        assertEquals(200, r.getStatusCode().value());
        List<?> data = (List<?>) extractData(r);
        assertEquals(1, data.size()); // only the "new" message survives
    }

    @Test
    void getConversationsUsesLegacyRedisKeyWhenActiveKeyMissing() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn(null);
        when(ops.get("user:1:current_conversation")).thenReturn("conv-legacy");
        when(conversationMemory.loadHistory("conv-legacy")).thenReturn(List.of());

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
    }

    @Test
    void getConversationsReturnsEmptyWhenHistoryIsEmpty() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn("conv-abc");
        when(conversationMemory.loadHistory("conv-abc")).thenReturn(List.of());

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(((List<?>) extractData(r)).isEmpty());
    }
```

Add the field and its setup in `setUp()`. In the field declarations (after line 30), add:
```java
    private com.yizhaoqi.roboknow.memory.ConversationMemory conversationMemory;
```
In `setUp()`, after the `userRepository = mock(...)` line, add:
```java
        conversationMemory = mock(com.yizhaoqi.roboknow.memory.ConversationMemory.class);
```
And after the `ReflectionTestUtils.setField(controller, "userRepository", userRepository);` line, add:
```java
        ReflectionTestUtils.setField(controller, "conversationMemory", conversationMemory);
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=ConversationControllerTest`
Expected: FAIL — `ConversationController` has no `conversationMemory` field yet, `ReflectionTestUtils.setField` throws.

- [ ] **Step 3: Update `ConversationController`**

Add the field (after the existing `objectMapper` field, around line 45):

```java
    @Autowired
    private com.yizhaoqi.roboknow.memory.ConversationMemory conversationMemory;
```

Replace `getConversationsFromRedis()` (lines 128-221) with a version that reads through `conversationMemory` instead of `redisTemplate`:

```java
    private ResponseEntity<?> getConversationsFromRedis(String conversationId, String username, String start_date, String end_date, LogUtils.PerformanceMonitor monitor) {
        List<Map<String, String>> history = conversationMemory.loadHistory(conversationId);

        List<Map<String, Object>> formattedConversations = new ArrayList<>();
        if (!history.isEmpty()) {
            // 解析时间范围
            LocalDateTime startDateTime = null;
            LocalDateTime endDateTime = null;

            if (start_date != null && !start_date.trim().isEmpty()) {
                try {
                    startDateTime = parseDateTime(start_date);
                    LogUtils.logBusiness("GET_CONVERSATIONS", username, "解析起始时间: %s -> %s", start_date, startDateTime);
                } catch (Exception e) {
                    LogUtils.logBusinessError("GET_CONVERSATIONS", username, "起始时间解析失败: %s", e, start_date);
                    throw new CustomException("起始时间格式错误: " + start_date, HttpStatus.BAD_REQUEST);
                }
            }

            if (end_date != null && !end_date.trim().isEmpty()) {
                try {
                    endDateTime = parseDateTime(end_date);
                    LogUtils.logBusiness("GET_CONVERSATIONS", username, "解析结束时间: %s -> %s", end_date, endDateTime);
                } catch (Exception e) {
                    LogUtils.logBusinessError("GET_CONVERSATIONS", username, "结束时间解析失败: %s", e, end_date);
                    throw new CustomException("结束时间格式错误: " + end_date, HttpStatus.BAD_REQUEST);
                }
            }

            // 将对话转换为前端需要的格式，使用存储的时间戳并进行时间过滤
            for (Map<String, String> message : history) {
                String messageTimestamp = message.getOrDefault("timestamp", "未知时间");

                // 时间过滤
                if (startDateTime != null || endDateTime != null) {
                    if (!"未知时间".equals(messageTimestamp)) {
                        try {
                            LocalDateTime messageDateTime = LocalDateTime.parse(messageTimestamp,
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                            if (startDateTime != null && messageDateTime.isBefore(startDateTime)) {
                                continue;
                            }
                            if (endDateTime != null && messageDateTime.isAfter(endDateTime)) {
                                continue;
                            }
                        } catch (Exception e) {
                            LogUtils.logBusinessError("GET_CONVERSATIONS", username, "消息时间戳格式错误: %s", e, messageTimestamp);
                        }
                    } else if (startDateTime != null || endDateTime != null) {
                        continue;
                    }
                }

                Map<String, Object> messageWithTimestamp = new HashMap<>();
                messageWithTimestamp.put("role", message.get("role"));
                messageWithTimestamp.put("content", message.get("content"));
                messageWithTimestamp.put("timestamp", messageTimestamp);
                formattedConversations.add(messageWithTimestamp);
            }

            LogUtils.logBusiness("GET_CONVERSATIONS", username, "获取到 %d 条对话记录，过滤后剩余 %d 条，会话ID: %s",
                    history.size(), formattedConversations.size(), conversationId);
            LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_history", "SUCCESS");
            monitor.end("获取对话历史成功");
        } else {
            LogUtils.logBusiness("GET_CONVERSATIONS", username, "会话ID %s 没有对应的历史记录", conversationId);
            LogUtils.logUserOperation(username, "GET_CONVERSATIONS", "conversation_history", "SUCCESS_EMPTY");
            monitor.end("获取对话历史成功（空结果）");
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "获取对话历史成功");
        response.put("data", formattedConversations);
        return ResponseEntity.ok().body(response);
    }
```

Remove the now-unused `JsonProcessingException` and `ObjectMapper`/`TypeReference` imports/usages if the class no longer references them elsewhere in the file — check with `grep -n "objectMapper\|JsonProcessingException\|TypeReference" src/main/java/com/yizhaoqi/roboknow/controller/ConversationController.java` before removing; keep the field if still used elsewhere in the file (it is not, after this change, so remove the `objectMapper` field and its two imports).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=ConversationControllerTest`
Expected: PASS (all tests, including the untouched session-CRUD tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/controller/ConversationController.java src/test/java/com/yizhaoqi/roboknow/controller/ConversationControllerTest.java
git commit -m "refactor(conversation): read history through ConversationMemory instead of raw Redis"
```

---

### Task 6: Fix `AdminController.getAllConversations()` to use the same session-aware path

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/controller/AdminController.java`
- Test: `src/test/java/com/yizhaoqi/roboknow/controller/AdminControllerConversationTest.java` (new file — scoped to just this endpoint to avoid touching the large existing `AdminController` test surface, if any)

**Context:** The current implementation scans Redis keys matching `user:*:current_conversation` — the **legacy** single-session key pattern from before the multi-session refactor (`2026-06-10-memory-system-design.md`). Users created via `SessionManager` use `user:{userId}:active_conversation`, so admin queries currently return 0 rows for any user using the current session system (confirmed live: an admin query for a user with 9 known MySQL conversation rows returned "共获取到 0 条对话记录"). This task fixes that by walking `ConversationSessionRepository` instead of scanning Redis key patterns.

**Interfaces:**
- Consumes: `ConversationSessionRepository.findAll()`, `ConversationSessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc(String, Status)` (existing), `ConversationMemory.loadHistory(String convId)` (Task 4).

- [ ] **Step 1: Write the failing test**

```java
package com.yizhaoqi.roboknow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.memory.ConversationMemory;
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.UserRepository;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminControllerConversationTest {

    private AdminController controller;
    private UserRepository userRepository;
    private JwtUtils jwtUtils;
    private ConversationSessionRepository sessionRepository;
    private ConversationMemory conversationMemory;

    @BeforeEach
    void setUp() {
        controller = new AdminController();
        userRepository = mock(UserRepository.class);
        jwtUtils = mock(JwtUtils.class);
        sessionRepository = mock(ConversationSessionRepository.class);
        conversationMemory = mock(ConversationMemory.class);

        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(controller, "conversationMemory", conversationMemory);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());

        when(jwtUtils.extractUsernameFromToken("valid")).thenReturn("admin");
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
    }

    @Test
    void getAllConversationsForSpecificUserReadsThroughConversationMemory() {
        User target = new User();
        target.setId(2L);
        target.setUsername("bob");
        target.setRole(User.Role.USER);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        ConversationSession session = new ConversationSession();
        session.setId("conv-1");
        session.setUserId("bob");
        session.setStatus(ConversationSession.Status.ACTIVE);
        when(sessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc("bob", ConversationSession.Status.ACTIVE))
                .thenReturn(List.of(session));
        when(conversationMemory.loadHistory("conv-1")).thenReturn(
                List.of(Map.of("role", "user", "content", "hi", "timestamp", "2026-07-01T10:00:00")));

        ResponseEntity<?> r = controller.getAllConversations("Bearer valid", "2", null, null);

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>)
                ((Map<String, Object>) r.getBody()).get("data");
        assertEquals(1, data.size());
        assertEquals("bob", data.get(0).get("username"));
        assertEquals("hi", data.get(0).get("content"));
    }

    @Test
    void getAllConversationsReturns404ForUnknownTargetUser() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> r = controller.getAllConversations("Bearer valid", "99", null, null);

        assertEquals(404, r.getStatusCode().value());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AdminControllerConversationTest`
Expected: FAIL — `AdminController` has no `sessionRepository`/`conversationMemory` fields yet, and current implementation reads via `redisTemplate.keys(...)` so the mocked session repository is never consulted.

- [ ] **Step 3: Update `AdminController`**

Add fields (after the existing `redisTemplate`/`objectMapper` fields, around line 47):

```java
    @Autowired
    private com.yizhaoqi.roboknow.repository.ConversationSessionRepository sessionRepository;

    @Autowired
    private com.yizhaoqi.roboknow.memory.ConversationMemory conversationMemory;
```

Replace the body of `getAllConversations()` from the `// 获取所有Redis键中以"user:"开头的键` comment through the end of the try block's `return ResponseEntity.ok().body(response);` (the block currently at lines 455-493) with:

```java
            List<com.yizhaoqi.roboknow.model.ConversationSession> sessions;
            if (targetUsername != null) {
                sessions = sessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc(
                        targetUsername, com.yizhaoqi.roboknow.model.ConversationSession.Status.ACTIVE);
            } else {
                sessions = sessionRepository.findByStatusOrderByLastActiveAtDesc(
                        com.yizhaoqi.roboknow.model.ConversationSession.Status.ACTIVE);
            }

            for (com.yizhaoqi.roboknow.model.ConversationSession session : sessions) {
                List<Map<String, String>> history = conversationMemory.loadHistory(session.getId());
                addHistoryToResult(history, allConversations, session.getUserId(), start_date, end_date);
            }

            LogUtils.logBusiness("ADMIN_GET_ALL_CONVERSATIONS", adminUsername, "管理员查询完成，共获取到 %d 条对话记录", allConversations.size());
            LogUtils.logUserOperation(adminUsername, "ADMIN_GET_ALL_CONVERSATIONS", "conversation_history", "SUCCESS");
            monitor.end("管理员查询对话历史成功");

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取对话历史成功");
            response.put("data", allConversations);
            return ResponseEntity.ok().body(response);
```

Rename the existing helper `processRedisConversation(String json, ...)` to a new helper that operates on the already-decoded history list instead of raw JSON (the JSON decoding responsibility moved into `ConversationMemory`). Replace the whole `processRedisConversation` method (originally around lines 509-...) with:

```java
    /**
     * 把已解析的历史消息按时间过滤后追加到结果列表，并附带用户名
     */
    private void addHistoryToResult(List<Map<String, String>> history, List<Map<String, Object>> targetList,
                                     String username, String startDate, String endDate) {
        java.time.LocalDateTime startDateTime = null;
        java.time.LocalDateTime endDateTime = null;

        if (startDate != null && !startDate.trim().isEmpty()) {
            try {
                startDateTime = parseDateTime(startDate);
            } catch (Exception e) {
                LogUtils.logBusinessError("ADMIN_GET_ALL_CONVERSATIONS", username, "起始时间解析失败: %s", e, startDate);
            }
        }

        if (endDate != null && !endDate.trim().isEmpty()) {
            try {
                endDateTime = parseDateTime(endDate);
            } catch (Exception e) {
                LogUtils.logBusinessError("ADMIN_GET_ALL_CONVERSATIONS", username, "结束时间解析失败: %s", e, endDate);
            }
        }

        for (Map<String, String> message : history) {
            String messageTimestamp = message.getOrDefault("timestamp", "未知时间");

            if (startDateTime != null || endDateTime != null) {
                if (!"未知时间".equals(messageTimestamp)) {
                    try {
                        java.time.LocalDateTime messageDateTime = java.time.LocalDateTime.parse(messageTimestamp,
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
                        if (startDateTime != null && messageDateTime.isBefore(startDateTime)) {
                            continue;
                        }
                        if (endDateTime != null && messageDateTime.isAfter(endDateTime)) {
                            continue;
                        }
                    } catch (Exception e) {
                        LogUtils.logBusinessError("ADMIN_GET_ALL_CONVERSATIONS", username, "消息时间戳格式错误: %s", e, messageTimestamp);
                    }
                } else {
                    continue;
                }
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("username", username);
            entry.put("role", message.get("role"));
            entry.put("content", message.get("content"));
            entry.put("timestamp", messageTimestamp);
            targetList.add(entry);
        }
    }
```

Remove the now-unused `redisTemplate.keys(...)` based scanning code and the `import com.fasterxml.jackson.core.JsonProcessingException;` / `TypeReference` usage if `processRedisConversation` was their only caller — check with `grep -n "JsonProcessingException\|TypeReference\|redisTemplate" src/main/java/com/yizhaoqi/roboknow/controller/AdminController.java` first; keep `redisTemplate` field if other endpoints in the same controller still use it (check before deleting the field itself — only remove the now-dead `processRedisConversation` method's imports, not the field, unless nothing else in the file uses `redisTemplate`).

- [ ] **Step 4: Add the missing repository method**

`findByStatusOrderByLastActiveAtDesc` doesn't exist yet on `ConversationSessionRepository`. Add it in `src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java`, after the existing `findByUserIdAndStatusOrderByLastActiveAtDesc` method:

```java
    List<ConversationSession> findByStatusOrderByLastActiveAtDesc(ConversationSession.Status status);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=AdminControllerConversationTest`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/controller/AdminController.java src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java src/test/java/com/yizhaoqi/roboknow/controller/AdminControllerConversationTest.java
git commit -m "fix(admin): read conversation history via session table instead of stale legacy Redis key scan"
```

---

### Task 7: Remove dead LTM code (`Conversation`, `ConversationRepository`, `ConversationService`)

**Files:**
- Delete: `src/main/java/com/yizhaoqi/roboknow/model/Conversation.java`
- Delete: `src/main/java/com/yizhaoqi/roboknow/repository/ConversationRepository.java`
- Delete: `src/main/java/com/yizhaoqi/roboknow/service/ConversationService.java`
- Delete: `src/test/java/com/yizhaoqi/roboknow/service/ConversationServiceCoverageTest.java`

- [ ] **Step 1: Confirm no remaining references**

Run: `grep -rln "ConversationService\|ConversationRepository\b\|model\.Conversation\b" src/main src/test`
Expected: only the four files listed above (the ones about to be deleted). If anything else shows up, stop and investigate before deleting — it means something still depends on this code.

- [ ] **Step 2: Delete the files**

```bash
git rm src/main/java/com/yizhaoqi/roboknow/model/Conversation.java src/main/java/com/yizhaoqi/roboknow/repository/ConversationRepository.java src/main/java/com/yizhaoqi/roboknow/service/ConversationService.java src/test/java/com/yizhaoqi/roboknow/service/ConversationServiceCoverageTest.java
```

- [ ] **Step 3: Run full test suite to verify nothing broke**

Run: `mvn test`
Expected: PASS, no compile errors, no missing bean errors.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(memory): remove unused pre-refactor Conversation LTM code"
```

---

### Task 8: Drop the orphaned `conversations` table in the dev database

**Files:** none (manual DB operation, dev environment only — no migration framework in this project)

- [ ] **Step 1: Confirm the table is really the old one and safe to drop**

Run: `docker exec mysql mysql -uroot -pRoboKnow2025 -e "SELECT COUNT(*) FROM RoboKnow.conversations;"`
Expected: `9` (matches the count identified during investigation — the legacy pre-refactor rows).

- [ ] **Step 2: Drop it**

Run: `docker exec mysql mysql -uroot -pRoboKnow2025 -e "DROP TABLE RoboKnow.conversations;"`
Expected: no output (success). This is safe because Task 7 removed the JPA entity mapped to this table, so `ddl-auto: update` will not try to recreate or reference it.

- [ ] **Step 3: Restart the backend and confirm it still starts cleanly**

Run: `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (or restart the already-running background process)
Expected: application starts without Hibernate schema errors, and logs show `conversation_messages` table validated/created (from Task 1's entity, since `ddl-auto: update` creates missing tables on boot).

---

### Task 9: End-to-end online verification

**Files:** none (manual verification against the running local stack)

- [ ] **Step 1: Confirm services are up**

Run: `docker ps --format "table {{.Names}}\t{{.Status}}"`
Expected: `mysql`, `redis` both healthy.

- [ ] **Step 2: Drive one real chat round through the running app**

Use the frontend (or `curl`/Postman against `POST` chat endpoint / WebSocket) logged in as an existing test user, send one message, get a response.

- [ ] **Step 3: Verify the write landed in MySQL**

Run: `docker exec mysql mysql -uroot -pRoboKnow2025 -e "SELECT conv_id, seq, role, LEFT(content,30), created_at FROM RoboKnow.conversation_messages ORDER BY id DESC LIMIT 4;"`
Expected: the just-sent user message and assistant reply, `seq` values 0/1 (or continuing from prior count if the conversation already had messages), roles alternating `user`/`assistant`.

- [ ] **Step 4: Verify the TTL-expiry recovery path actually works**

Run: `docker exec redis redis-cli -a RoboKnow2025 DEL "conversation:<the convId used in step 2>"` (get the convId from the app's session list, or from the `conv_id` column in step 3)
Then re-fetch the conversation through the app (reload the chat view, or `GET /api/v1/users/conversation`).
Expected: the message from step 2 is still visible — proves the cache-aside fallback actually recovers "lost" (expired) history from MySQL, which is the original bug this whole plan exists to fix.

- [ ] **Step 5: Verify Redis was backfilled by the fallback**

Run: `docker exec redis redis-cli -a RoboKnow2025 GET "conversation:<the convId>"`
Expected: non-empty JSON — confirms `loadHistory()`'s cache-aside backfill (Task 4) actually re-wrote Redis after the DB fallback, not just served the DB result once.

- [ ] **Step 6: Verify the admin endpoint sees the same data**

Log in as an admin user, call `GET /api/v1/admin/conversation?userid=<the test user's numeric id>`.
Expected: HTTP 200, response `data` includes the message from step 2 — proves Task 6's fix (reading via `ConversationSessionRepository` + `ConversationMemory` instead of the stale legacy Redis key pattern) actually surfaces conversations for users on the current multi-session system.

- [ ] **Step 7: Record the verification result**

If all six checks above pass, the feature is verified end-to-end against the running local environment. No commit needed for this task — it's a verification gate, not a code change.

---

## Post-plan check

After Task 9 passes, do a final full-suite run to catch any regression introduced across tasks:

Run: `mvn test`
Expected: PASS, full green build.
