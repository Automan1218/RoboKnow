# Memory System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor ReactAgentService's tangled memory logic into a clean facade + 5-component architecture supporting multi-session, token-budget STM eviction, async Map-Reduce compression, and deduplicated LTM facts.

**Architecture:** MemoryManager facade wraps ConversationMemory (Redis STM), LongTermMemory (MySQL facts), TokenBudget (jtokkit), ContextCompressor (async LLM), and MemoryRetriever (keyword+recency). SessionManager handles multi-session lifecycle independently. ReactAgentService becomes memory-free, delegating entirely to MemoryManager.

**Tech Stack:** Spring Boot 3.4.2, Java 17, Redis (Spring Data), MySQL (JPA), jtokkit 1.1.0 (token counting), @Async ThreadPoolTaskExecutor, @Scheduled, OpenAiClient (existing)

---

## File Map

### New files
| File | Responsibility |
|------|---------------|
| `src/main/java/com/yizhaoqi/roboknow/config/AsyncConfig.java` | Thread pool `memoryExecutor` + enable scheduling |
| `src/main/java/com/yizhaoqi/roboknow/model/ConversationSession.java` | JPA entity for `conversation_sessions` table |
| `src/main/java/com/yizhaoqi/roboknow/model/UserMemoryFact.java` | JPA entity for `user_memory_facts` table |
| `src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java` | CRUD + find by userId/status |
| `src/main/java/com/yizhaoqi/roboknow/repository/UserMemoryFactRepository.java` | Find by userId, dedup by content_hash |
| `src/main/java/com/yizhaoqi/roboknow/memory/TokenBudget.java` | Stateless token counting via jtokkit |
| `src/main/java/com/yizhaoqi/roboknow/memory/ConversationMemory.java` | Redis STM: read/write/evict with write lock |
| `src/main/java/com/yizhaoqi/roboknow/memory/LongTermMemory.java` | MySQL facts: store with content-hash dedup |
| `src/main/java/com/yizhaoqi/roboknow/memory/MemoryRetriever.java` | Keyword+recency Top-K fact retrieval |
| `src/main/java/com/yizhaoqi/roboknow/memory/ContextCompressor.java` | Async Map-Reduce STM compression + fact extraction |
| `src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java` | Facade: loadContext + record |
| `src/main/java/com/yizhaoqi/roboknow/service/SessionManager.java` | Session lifecycle: create/list/switch/delete/getActive |

### Modified files
| File | Change |
|------|--------|
| `pom.xml` | Add jtokkit 1.1.0 dependency |
| `src/main/resources/application.yml` | Add `memory.*` config block |
| `src/main/java/com/yizhaoqi/roboknow/agent/ReactAgentService.java` | Remove memory code, inject MemoryManager, accept convId |
| `src/main/java/com/yizhaoqi/roboknow/service/ChatHandler.java` | Add convId parameter |
| `src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java` | Parse convId from JSON message, call SessionManager |
| `src/main/java/com/yizhaoqi/roboknow/controller/ConversationController.java` | Add session CRUD endpoints |

---

## Task 1: jtokkit dependency + AsyncConfig + application.yml config

**Files:**
- Modify: `pom.xml` (after jackson-databind dependency)
- Create: `src/main/java/com/yizhaoqi/roboknow/config/AsyncConfig.java`
- Modify: `src/main/resources/application.yml` (append memory block)

- [ ] **Step 1: Add jtokkit to pom.xml**

Find the closing `</dependencies>` tag in `pom.xml` and insert before it:

```xml
        <!-- jtokkit: accurate token counting for OpenAI models -->
        <dependency>
            <groupId>com.knuddels</groupId>
            <artifactId>jtokkit</artifactId>
            <version>1.1.0</version>
        </dependency>
```

- [ ] **Step 2: Append memory config to application.yml**

Append to the end of `src/main/resources/application.yml`:

```yaml
memory:
  token-budget: 8192        # max tokens reserved for conversation history
  context-window: 10        # recent messages always included verbatim
  ltm-top-k: 3              # max facts injected per request
  compress-threshold: 0.80  # trigger compression when usage > 80%
  extract-every-n-rounds: 10 # incremental fact extraction interval
  idle-timeout-minutes: 30  # session idle before fact extraction
```

- [ ] **Step 3: Create AsyncConfig.java**

```java
package com.yizhaoqi.roboknow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "memoryExecutor")
    public Executor memoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("memory-async-");
        executor.setRejectedExecutionHandler((r, e) -> {
            // silently drop when queue full — memory ops are best-effort
        });
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS (jtokkit resolves from Maven Central)

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/yizhaoqi/roboknow/config/AsyncConfig.java
git commit -m "feat(memory): add jtokkit dep, AsyncConfig, memory config"
```

---

## Task 2: ConversationSession entity + repository

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/model/ConversationSession.java`
- Create: `src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java`

- [ ] **Step 1: Create ConversationSession.java**

```java
package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_sessions", indexes = {
        @Index(name = "idx_cs_user_id", columnList = "user_id"),
        @Index(name = "idx_cs_last_active", columnList = "last_active_at")
})
public class ConversationSession {

    @Id
    @Column(length = 36)
    private String id; // UUID, doubles as Redis convId

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId; // username (matches Redis key convention)

    @Column(length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(name = "round_count")
    private int roundCount = 0; // tracks rounds for incremental extraction

    public enum Status {
        ACTIVE, ARCHIVED
    }
}
```

- [ ] **Step 2: Create ConversationSessionRepository.java**

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {

    List<ConversationSession> findByUserIdAndStatusOrderByLastActiveAtDesc(
            String userId, ConversationSession.Status status);

    Optional<ConversationSession> findTopByUserIdAndStatusOrderByLastActiveAtDesc(
            String userId, ConversationSession.Status status);

    @Query("SELECT s FROM ConversationSession s WHERE s.status = 'ACTIVE' " +
           "AND s.lastActiveAt < :cutoff")
    List<ConversationSession> findIdleSessions(@Param("cutoff") LocalDateTime cutoff);
}
```

- [ ] **Step 3: Start app and verify JPA creates the table**

```bash
mvn spring-boot:run &
# Wait ~20 seconds then check MySQL
# Expected: conversation_sessions table created with all columns
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/model/ConversationSession.java \
        src/main/java/com/yizhaoqi/roboknow/repository/ConversationSessionRepository.java
git commit -m "feat(memory): add ConversationSession entity and repository"
```

---

## Task 3: UserMemoryFact entity + repository

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/model/UserMemoryFact.java`
- Create: `src/main/java/com/yizhaoqi/roboknow/repository/UserMemoryFactRepository.java`

- [ ] **Step 1: Create UserMemoryFact.java**

```java
package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_memory_facts", indexes = {
        @Index(name = "idx_umf_user_id", columnList = "user_id"),
        @Index(name = "idx_umf_content_hash", columnList = "content_hash"),
        @Index(name = "idx_umf_created_at", columnList = "created_at")
})
public class UserMemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash; // MD5 hex of normalized content for dedup

    @Column(name = "source_conversation_id", length = 36)
    private String sourceConversationId;

    @Column(name = "hit_count")
    private int hitCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create UserMemoryFactRepository.java**

```java
package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.UserMemoryFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserMemoryFactRepository extends JpaRepository<UserMemoryFact, Long> {

    Optional<UserMemoryFact> findByUserIdAndContentHash(String userId, String contentHash);

    List<UserMemoryFact> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Query("UPDATE UserMemoryFact f SET f.hitCount = f.hitCount + 1 WHERE f.id = :id")
    void incrementHitCount(@Param("id") Long id);
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/model/UserMemoryFact.java \
        src/main/java/com/yizhaoqi/roboknow/repository/UserMemoryFactRepository.java
git commit -m "feat(memory): add UserMemoryFact entity and repository"
```

---

## Task 4: TokenBudget component

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/TokenBudget.java`

- [ ] **Step 1: Create TokenBudget.java**

```java
package com.yizhaoqi.roboknow.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TokenBudget {

    private final int budget;
    private final Encoding encoding;

    public TokenBudget(@Value("${memory.token-budget:8192}") int budget) {
        this.budget = budget;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        // cl100k_base covers gpt-4o, gpt-4o-mini, gpt-3.5-turbo, deepseek models
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return encoding.countTokens(text);
    }

    public int countMessagesTokens(List<Map<String, String>> messages) {
        int total = 0;
        for (Map<String, String> msg : messages) {
            String content = msg.getOrDefault("content", "");
            total += countTokens(content) + 4; // ~4 overhead per message (role, separators)
        }
        return total;
    }

    public double getUsageRatio(List<Map<String, String>> messages) {
        return (double) countMessagesTokens(messages) / budget;
    }

    public int getBudget() {
        return budget;
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/TokenBudget.java
git commit -m "feat(memory): add TokenBudget component with jtokkit cl100k_base"
```

---

## Task 5: ConversationMemory (Redis STM)

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/ConversationMemory.java`

- [ ] **Step 1: Create ConversationMemory.java**

```java
package com.yizhaoqi.roboknow.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConversationMemory {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemory.class);
    private static final Duration CONV_TTL = Duration.ofDays(7);
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final int MAX_LOCK_RETRIES = 3;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final StringRedisTemplate redis;
    private final TokenBudget tokenBudget;
    private final ObjectMapper objectMapper;

    @Value("${memory.compress-threshold:0.80}")
    private double compressThreshold;

    public ConversationMemory(StringRedisTemplate redis,
                               TokenBudget tokenBudget,
                               ObjectMapper objectMapper) {
        this.redis = redis;
        this.tokenBudget = tokenBudget;
        this.objectMapper = objectMapper;
    }

    /** Load full history for a conversation. Returns empty list on miss. */
    public List<Map<String, String>> loadHistory(String convId) {
        String json = redis.opsForValue().get(historyKey(convId));
        if (json == null) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("Failed to parse conversation history convId={}: {}", convId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Load the STM summary (compressed older messages). */
    public String loadSummary(String convId) {
        try {
            return redis.opsForValue().get(summaryKey(convId));
        } catch (Exception e) {
            logger.warn("Failed to read STM summary convId={}: {}", convId, e.getMessage());
            return null;
        }
    }

    /**
     * Atomically append user+assistant message pair and return whether
     * compression is needed (usage ratio exceeded threshold after write).
     * Returns the messages that were evicted to pending_compress (empty if none).
     */
    public List<Map<String, String>> appendAndEvictIfNeeded(String convId,
                                                             String userMessage,
                                                             String assistantMessage) {
        String lockKey = lockKey(convId);
        boolean locked = acquireLock(lockKey);
        if (!locked) {
            logger.warn("Could not acquire write lock for convId={}, writing without lock", convId);
        }
        try {
            List<Map<String, String>> history = loadHistory(convId);
            String ts = LocalDateTime.now().format(TS_FMT);

            Map<String, String> um = new HashMap<>();
            um.put("role", "user");
            um.put("content", userMessage);
            um.put("timestamp", ts);
            history.add(um);

            Map<String, String> am = new HashMap<>();
            am.put("role", "assistant");
            am.put("content", assistantMessage);
            am.put("timestamp", ts);
            history.add(am);

            List<Map<String, String>> evicted = new ArrayList<>();
            if (tokenBudget.getUsageRatio(history) > compressThreshold) {
                // FIFO evict from head until under threshold
                while (tokenBudget.getUsageRatio(history) > compressThreshold && history.size() > 2) {
                    evicted.add(history.remove(0));
                }
                if (!evicted.isEmpty()) {
                    addToPendingCompress(convId, evicted);
                    logger.debug("Evicted {} messages to pending_compress for convId={}", evicted.size(), convId);
                }
            }

            saveHistory(convId, history);
            return evicted;
        } finally {
            if (locked) releaseLock(lockKey);
        }
    }

    /** Retrieve pending messages awaiting compression. */
    public List<Map<String, String>> loadPendingCompress(String convId) {
        String json = redis.opsForValue().get(pendingKey(convId));
        if (json == null) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            logger.warn("Failed to parse pending_compress convId={}: {}", convId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Clear the pending_compress buffer after successful compression. */
    public void clearPendingCompress(String convId) {
        redis.delete(pendingKey(convId));
    }

    /** Write the new STM summary. */
    public void saveSummary(String convId, String summary) {
        redis.opsForValue().set(summaryKey(convId), summary, CONV_TTL);
    }

    /** Delete all Redis keys for a conversation (called on deleteSession). */
    public void deleteAllKeys(String convId) {
        redis.delete(historyKey(convId));
        redis.delete(summaryKey(convId));
        redis.delete(pendingKey(convId));
        redis.delete(lockKey(convId));
    }

    // ── private helpers ────────────────────────────────────────────────────

    private void saveHistory(String convId, List<Map<String, String>> history) {
        try {
            redis.opsForValue().set(historyKey(convId), objectMapper.writeValueAsString(history), CONV_TTL);
        } catch (Exception e) {
            logger.error("Failed to save conversation history convId={}: {}", convId, e.getMessage());
        }
    }

    private void addToPendingCompress(String convId, List<Map<String, String>> messages) {
        try {
            List<Map<String, String>> existing = loadPendingCompress(convId);
            existing.addAll(messages);
            redis.opsForValue().set(pendingKey(convId), objectMapper.writeValueAsString(existing), CONV_TTL);
        } catch (Exception e) {
            logger.warn("Failed to update pending_compress convId={}: {}", convId, e.getMessage());
        }
    }

    private boolean acquireLock(String lockKey) {
        for (int i = 0; i < MAX_LOCK_RETRIES; i++) {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
            if (Boolean.TRUE.equals(ok)) return true;
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return false;
    }

    private void releaseLock(String lockKey) {
        redis.delete(lockKey);
    }

    private String historyKey(String convId)  { return "conversation:" + convId; }
    private String summaryKey(String convId)  { return "conversation:" + convId + ":stm_summary"; }
    private String pendingKey(String convId)  { return "conversation:" + convId + ":pending_compress"; }
    private String lockKey(String convId)     { return "conversation:" + convId + ":write_lock"; }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/ConversationMemory.java
git commit -m "feat(memory): add ConversationMemory with token-budget eviction and write lock"
```

---

## Task 6: LongTermMemory (MySQL facts + dedup)

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/LongTermMemory.java`

- [ ] **Step 1: Create LongTermMemory.java**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.UserMemoryFact;
import com.yizhaoqi.roboknow.repository.UserMemoryFactRepository;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemory.class);

    private final UserMemoryFactRepository factRepository;

    public LongTermMemory(UserMemoryFactRepository factRepository) {
        this.factRepository = factRepository;
    }

    /**
     * Store a fact for the user. Deduplicates by MD5 hash of normalized content.
     * If identical fact exists, just updates updated_at (no duplicate row).
     */
    @Transactional
    public void storeFact(String userId, String content, String sourceConvId) {
        if (content == null || content.isBlank()) return;
        String normalized = content.trim().toLowerCase();
        String hash = DigestUtils.md5Hex(normalized);

        Optional<UserMemoryFact> existing = factRepository.findByUserIdAndContentHash(userId, hash);
        if (existing.isPresent()) {
            // touch updated_at via a no-op save (UpdateTimestamp fires)
            factRepository.save(existing.get());
            logger.debug("LTM fact already exists for userId={}, skipping duplicate", userId);
            return;
        }

        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(userId);
        fact.setContent(content.trim());
        fact.setContentHash(hash);
        fact.setSourceConversationId(sourceConvId);
        factRepository.save(fact);
        logger.debug("LTM fact stored for userId={}: {}", userId, content.substring(0, Math.min(80, content.length())));
    }

    /** Load all facts for a user, newest first. */
    public List<UserMemoryFact> loadFacts(String userId) {
        return factRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Increment hit count on a retrieved fact (for recency+popularity scoring). */
    @Transactional
    public void recordHit(Long factId) {
        try {
            factRepository.incrementHitCount(factId);
        } catch (Exception e) {
            logger.warn("Failed to increment hit count for factId={}: {}", factId, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS. `DigestUtils` comes from commons-codec which is already in pom.xml.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/LongTermMemory.java
git commit -m "feat(memory): add LongTermMemory with MD5 dedup"
```

---

## Task 7: MemoryRetriever (keyword + recency)

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/MemoryRetriever.java`

- [ ] **Step 1: Create MemoryRetriever.java**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.UserMemoryFact;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MemoryRetriever {

    private static final Set<String> STOP_WORDS = Set.of(
            "the","a","an","is","are","was","were","i","you","he","she","it","we","they",
            "what","how","why","when","where","who","do","does","did","have","has","had",
            "can","could","will","would","should","may","might","this","that","these","those",
            "我","你","他","她","它","我们","你们","他们","是","的","了","吗","吧","呢","在","和","有"
    );

    @Value("${memory.ltm-top-k:3}")
    private int topK;

    private final LongTermMemory longTermMemory;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    /**
     * Retrieve top-K relevant facts for the given user and message.
     * Facts with score = 0 (no keyword match) are excluded.
     */
    public List<UserMemoryFact> retrieve(String userId, String userMessage) {
        List<UserMemoryFact> allFacts = longTermMemory.loadFacts(userId);
        if (allFacts.isEmpty()) return List.of();

        Set<String> queryWords = tokenize(userMessage);
        if (queryWords.isEmpty()) return List.of();

        LocalDateTime now = LocalDateTime.now();

        return allFacts.stream()
                .map(fact -> new ScoredFact(fact, score(fact, queryWords, now)))
                .filter(sf -> sf.score > 0)
                .sorted(Comparator.comparingDouble(ScoredFact::score).reversed())
                .limit(topK)
                .map(sf -> sf.fact)
                .collect(Collectors.toList());
    }

    private double score(UserMemoryFact fact, Set<String> queryWords, LocalDateTime now) {
        Set<String> factWords = tokenize(fact.getContent());
        long matchCount = queryWords.stream().filter(factWords::contains).count();
        double keywordScore = queryWords.isEmpty() ? 0 : (double) matchCount / queryWords.size();

        long daysSince = ChronoUnit.DAYS.between(
                fact.getCreatedAt() != null ? fact.getCreatedAt() : now, now);
        double recencyScore = 1.0 / (1.0 + daysSince);

        double hitBonus = Math.min(0.2, fact.getHitCount() * 0.02);

        return 0.6 * keywordScore + 0.3 * recencyScore + 0.1 * hitBonus;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}，。！？、：；""'']+"))
                .filter(w -> w.length() > 1 && !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    private record ScoredFact(UserMemoryFact fact, double score) {}
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/MemoryRetriever.java
git commit -m "feat(memory): add MemoryRetriever with keyword+recency scoring"
```

---

## Task 8: ContextCompressor (async Map-Reduce + fact extraction)

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/ContextCompressor.java`

- [ ] **Step 1: Create ContextCompressor.java**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.client.OpenAiClient;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ContextCompressor {

    private static final Logger logger = LoggerFactory.getLogger(ContextCompressor.class);
    private static final int MAP_CHUNK_SIZE = 5;

    private final ConversationMemory conversationMemory;
    private final LongTermMemory longTermMemory;
    private final OpenAiClient openAiClient;
    private final ConversationSessionRepository sessionRepository;

    @Value("${memory.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    public ContextCompressor(ConversationMemory conversationMemory,
                              LongTermMemory longTermMemory,
                              OpenAiClient openAiClient,
                              ConversationSessionRepository sessionRepository) {
        this.conversationMemory = conversationMemory;
        this.longTermMemory = longTermMemory;
        this.openAiClient = openAiClient;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Async Map-Reduce STM compression.
     * Called by MemoryManager when eviction happened. Must NOT block request path.
     */
    @Async("memoryExecutor")
    public void compressAsync(String convId, String existingSummary) {
        List<Map<String, String>> pending = conversationMemory.loadPendingCompress(convId);
        if (pending.isEmpty()) return;

        try {
            // Map: compress 5-message chunks in parallel (serial for simplicity, low volume)
            List<String> partialSummaries = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += MAP_CHUNK_SIZE) {
                List<Map<String, String>> chunk = pending.subList(i, Math.min(i + MAP_CHUNK_SIZE, pending.size()));
                String partial = compressChunk(chunk, convId);
                if (partial != null && !partial.isBlank()) {
                    partialSummaries.add(partial);
                }
            }

            if (partialSummaries.isEmpty()) return;

            // Reduce: merge all partial summaries (+ existing summary if present)
            String finalSummary;
            if (partialSummaries.size() == 1 && (existingSummary == null || existingSummary.isBlank())) {
                finalSummary = partialSummaries.get(0);
            } else {
                finalSummary = reduceSummaries(existingSummary, partialSummaries, convId);
            }

            if (finalSummary != null && !finalSummary.isBlank()) {
                conversationMemory.saveSummary(convId, finalSummary);
                conversationMemory.clearPendingCompress(convId);
                logger.info("STM compression done for convId={}, {} messages compressed", convId, pending.size());
            }
        } catch (Exception e) {
            logger.warn("STM compression failed for convId={}: {} — pending_compress retained for retry",
                    convId, e.getMessage());
        }
    }

    /**
     * Async fact extraction from a conversation's recent messages.
     * Triggered by MemoryManager on incremental count or idle timeout.
     */
    @Async("memoryExecutor")
    public void extractFactsAsync(String userId, String convId) {
        List<Map<String, String>> history = conversationMemory.loadHistory(convId);
        if (history.isEmpty()) return;

        try {
            StringBuilder dialogue = new StringBuilder();
            for (Map<String, String> msg : history) {
                dialogue.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }

            List<Map<String, String>> req = List.of(
                Map.of("role", "system", "content",
                    "From the conversation below, extract distinct user preferences, decisions, " +
                    "or important facts as a numbered list. Each item must be a single concrete statement. " +
                    "Omit generic knowledge. Only extract what is specific to this user or project. " +
                    "If nothing specific, reply with exactly: NONE"),
                Map.of("role", "user", "content", dialogue.toString())
            );

            String result = openAiClient.chatBlocking(req,
                    new AiUsageMetadata(userId, convId, "fact_extraction"));
            if (result == null || result.isBlank() || result.strip().equals("NONE")) return;

            // Parse numbered list: "1. fact\n2. fact\n..."
            for (String line : result.split("\n")) {
                String fact = line.replaceFirst("^\\d+\\.\\s*", "").trim();
                if (!fact.isBlank() && fact.length() > 10) {
                    longTermMemory.storeFact(userId, fact, convId);
                }
            }
            logger.info("Fact extraction done for userId={} convId={}", userId, convId);
        } catch (Exception e) {
            logger.warn("Fact extraction failed for userId={} convId={}: {}", userId, convId, e.getMessage());
        }
    }

    /**
     * Scheduled scan: extract facts from sessions idle > idleTimeoutMinutes.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelay = 300_000)
    public void processIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);
        try {
            sessionRepository.findIdleSessions(cutoff).forEach(session -> {
                logger.debug("Idle session detected userId={} convId={}, triggering fact extraction",
                        session.getUserId(), session.getId());
                extractFactsAsync(session.getUserId(), session.getId());
            });
        } catch (Exception e) {
            logger.warn("Idle session scan failed: {}", e.getMessage());
        }
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private String compressChunk(List<Map<String, String>> chunk, String convId) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : chunk) {
            sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
        }
        List<Map<String, String>> req = List.of(
            Map.of("role", "system", "content",
                "Summarize the following conversation excerpt in 2-3 sentences, " +
                "preserving key facts, decisions, and context needed for the ongoing dialogue."),
            Map.of("role", "user", "content", sb.toString())
        );
        return openAiClient.chatBlocking(req, new AiUsageMetadata("system", convId, "stm_map"));
    }

    private String reduceSummaries(String existingSummary,
                                    List<String> partials, String convId) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("Existing summary:\n").append(existingSummary).append("\n\n");
        }
        sb.append("New partial summaries to merge:\n");
        for (int i = 0; i < partials.size(); i++) {
            sb.append(i + 1).append(". ").append(partials.get(i)).append("\n");
        }
        List<Map<String, String>> req = List.of(
            Map.of("role", "system", "content",
                "Merge all summaries into one coherent 3-5 sentence summary. " +
                "Preserve all key facts and decisions. Do not repeat information."),
            Map.of("role", "user", "content", sb.toString())
        );
        return openAiClient.chatBlocking(req, new AiUsageMetadata("system", convId, "stm_reduce"));
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/ContextCompressor.java
git commit -m "feat(memory): add async ContextCompressor with Map-Reduce + idle fact extraction"
```

---

## Task 9: MemoryManager facade

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java`

- [ ] **Step 1: Create MemoryManager.java**

```java
package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.UserMemoryFact;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);

    private final ConversationMemory conversationMemory;
    private final LongTermMemory longTermMemory;
    private final MemoryRetriever memoryRetriever;
    private final ContextCompressor contextCompressor;
    private final TokenBudget tokenBudget;
    private final ConversationSessionRepository sessionRepository;

    @Value("${memory.context-window:10}")
    private int contextWindow;

    @Value("${memory.extract-every-n-rounds:10}")
    private int extractEveryNRounds;

    public MemoryManager(ConversationMemory conversationMemory,
                          LongTermMemory longTermMemory,
                          MemoryRetriever memoryRetriever,
                          ContextCompressor contextCompressor,
                          TokenBudget tokenBudget,
                          ConversationSessionRepository sessionRepository) {
        this.conversationMemory = conversationMemory;
        this.longTermMemory = longTermMemory;
        this.memoryRetriever = memoryRetriever;
        this.contextCompressor = contextCompressor;
        this.tokenBudget = tokenBudget;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Build the context message list for the LLM.
     * Order: [system prompt] [LTM facts] [STM summary] [recent N messages] [user message]
     * The system prompt is injected by the caller (ReactAgentService).
     */
    public List<Map<String, String>> loadContext(String userId, String convId, String userMessage) {
        List<Map<String, String>> result = new ArrayList<>();

        // LTM: relevant facts
        List<UserMemoryFact> facts = memoryRetriever.retrieve(userId, userMessage);
        if (!facts.isEmpty()) {
            StringBuilder ltmBlock = new StringBuilder("Relevant facts about this user/project:\n");
            for (UserMemoryFact f : facts) {
                ltmBlock.append("- ").append(f.getContent()).append("\n");
                longTermMemory.recordHit(f.getId());
            }
            result.add(Map.of("role", "system", "content", ltmBlock.toString()));
        }

        // STM: compressed summary of older messages
        String stmSummary = conversationMemory.loadSummary(convId);
        if (stmSummary != null && !stmSummary.isBlank()) {
            result.add(Map.of("role", "system", "content",
                    "Summary of earlier conversation:\n" + stmSummary));
        }

        // Recent messages within context window
        List<Map<String, String>> history = conversationMemory.loadHistory(convId);
        int start = Math.max(0, history.size() - contextWindow);
        result.addAll(history.subList(start, history.size()));

        return result;
    }

    /**
     * Record a completed exchange and trigger async compression/extraction if needed.
     * Returns immediately — all heavy work is async.
     */
    @Transactional
    public void record(String userId, String convId, String question, String answer) {
        List<Map<String, String>> evicted =
                conversationMemory.appendAndEvictIfNeeded(convId, question, answer);

        if (!evicted.isEmpty()) {
            String existingSummary = conversationMemory.loadSummary(convId);
            contextCompressor.compressAsync(convId, existingSummary);
        }

        // Incremental fact extraction
        sessionRepository.findById(convId).ifPresent(session -> {
            session.setRoundCount(session.getRoundCount() + 1);
            if (session.getRoundCount() % extractEveryNRounds == 0) {
                logger.debug("Incremental fact extraction triggered at round={} for convId={}", 
                        session.getRoundCount(), convId);
                contextCompressor.extractFactsAsync(userId, convId);
            }
            sessionRepository.save(session);
        });
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/memory/MemoryManager.java
git commit -m "feat(memory): add MemoryManager facade (loadContext + record)"
```

---

## Task 10: SessionManager

**Files:**
- Create: `src/main/java/com/yizhaoqi/roboknow/service/SessionManager.java`

- [ ] **Step 1: Create SessionManager.java**

```java
package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.client.OpenAiClient;
import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.memory.ConversationMemory;
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final Duration ACTIVE_CONV_TTL = Duration.ofDays(30);
    private static final String ACTIVE_KEY_PREFIX = "user:";
    private static final String ACTIVE_KEY_SUFFIX = ":active_conversation";

    private final ConversationSessionRepository sessionRepository;
    private final StringRedisTemplate redis;
    private final ConversationMemory conversationMemory;
    private final OpenAiClient openAiClient;

    public SessionManager(ConversationSessionRepository sessionRepository,
                           StringRedisTemplate redis,
                           ConversationMemory conversationMemory,
                           OpenAiClient openAiClient) {
        this.sessionRepository = sessionRepository;
        this.redis = redis;
        this.conversationMemory = conversationMemory;
        this.openAiClient = openAiClient;
    }

    /** Create a new session, set it as active, return convId. */
    @Transactional
    public String createSession(String userId) {
        String convId = UUID.randomUUID().toString();
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId(userId);
        session.setTitle("New conversation");
        session.setStatus(ConversationSession.Status.ACTIVE);
        sessionRepository.save(session);
        setActiveConvId(userId, convId);
        logger.info("Created session convId={} for userId={}", convId, userId);
        return convId;
    }

    /** List active sessions for a user, newest first. */
    public List<ConversationSession> listSessions(String userId) {
        return sessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc(
                userId, ConversationSession.Status.ACTIVE);
    }

    /**
     * Switch active session. Verifies ownership — throws 403 if convId belongs to another user.
     */
    @Transactional
    public void switchSession(String userId, String convId) {
        ConversationSession session = sessionRepository.findById(convId)
                .orElseThrow(() -> new CustomException("Session not found", HttpStatus.NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }
        setActiveConvId(userId, convId);
        logger.info("Switched active session to convId={} for userId={}", convId, userId);
    }

    /**
     * Soft-delete a session (archive) and clean up all its Redis keys immediately.
     */
    @Transactional
    public void deleteSession(String userId, String convId) {
        ConversationSession session = sessionRepository.findById(convId)
                .orElseThrow(() -> new CustomException("Session not found", HttpStatus.NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }

        session.setStatus(ConversationSession.Status.ARCHIVED);
        sessionRepository.save(session);

        // Explicit Redis cleanup — do not rely on TTL
        conversationMemory.deleteAllKeys(convId);

        // If this was the active session, clear the pointer
        String currentActive = redis.opsForValue().get(activeKey(userId));
        if (convId.equals(currentActive)) {
            redis.delete(activeKey(userId));
        }
        logger.info("Deleted session convId={} for userId={}", convId, userId);
    }

    /**
     * Get current active convId for userId.
     * Redis → MySQL latest active → auto-create. Never returns null.
     */
    @Transactional
    public String getActiveConvId(String userId) {
        // Try Redis pointer first
        String cached = redis.opsForValue().get(activeKey(userId));
        if (cached != null && !cached.isBlank()) {
            // Migrate old key if needed (user:{userId}:current_conversation)
            return cached;
        }

        // Try MySQL latest active session
        return sessionRepository
                .findTopByUserIdAndStatusOrderByLastActiveAtDesc(userId, ConversationSession.Status.ACTIVE)
                .map(session -> {
                    setActiveConvId(userId, session.getId());
                    return session.getId();
                })
                .orElseGet(() -> createSession(userId)); // auto-create if no session exists
    }

    /**
     * Verify that convId belongs to userId. Throws 403 if not.
     */
    public void verifyOwnership(String userId, String convId) {
        ConversationSession session = sessionRepository.findById(convId)
                .orElseThrow(() -> new CustomException("Session not found", HttpStatus.NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Generate a title for the session asynchronously after the first exchange.
     */
    @Async("memoryExecutor")
    public void generateTitleAsync(String convId, String firstUserMessage) {
        try {
            String prompt = firstUserMessage.length() > 200
                    ? firstUserMessage.substring(0, 200) : firstUserMessage;
            String title = openAiClient.chatBlocking(
                List.of(
                    Map.of("role", "system", "content",
                        "Generate a short 4-8 word title for a conversation that starts with the following message. " +
                        "Reply with ONLY the title, no punctuation at the end."),
                    Map.of("role", "user", "content", prompt)
                ),
                new AiUsageMetadata("system", convId, "session_title")
            );
            if (title != null && !title.isBlank()) {
                String trimmed = title.trim().substring(0, Math.min(100, title.trim().length()));
                sessionRepository.findById(convId).ifPresent(s -> {
                    s.setTitle(trimmed);
                    sessionRepository.save(s);
                });
            }
        } catch (Exception e) {
            // Fallback: use first 30 chars of message
            String fallback = firstUserMessage.substring(0, Math.min(30, firstUserMessage.length()));
            sessionRepository.findById(convId).ifPresent(s -> {
                s.setTitle(fallback);
                sessionRepository.save(s);
            });
        }
    }

    /**
     * Migrate old Redis key user:{userId}:current_conversation → active_conversation.
     * Call once at startup or on first getActiveConvId call.
     */
    public void migrateOldKeyIfPresent(String userId) {
        String oldKey = "user:" + userId + ":current_conversation";
        String oldValue = redis.opsForValue().get(oldKey);
        if (oldValue != null && !oldValue.isBlank()) {
            String newKey = activeKey(userId);
            if (redis.opsForValue().get(newKey) == null) {
                redis.opsForValue().set(newKey, oldValue, ACTIVE_CONV_TTL);
            }
            redis.expire(oldKey, Duration.ofDays(1)); // let old key die naturally
        }
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private void setActiveConvId(String userId, String convId) {
        redis.opsForValue().set(activeKey(userId), convId, ACTIVE_CONV_TTL);
    }

    private String activeKey(String userId) {
        return ACTIVE_KEY_PREFIX + userId + ACTIVE_KEY_SUFFIX;
    }
}
```

Note: The `Map.of` in `generateTitleAsync` needs an import. Add `import java.util.Map;` to the imports.

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/service/SessionManager.java
git commit -m "feat(memory): add SessionManager with multi-session lifecycle"
```

---

## Task 11: Refactor ReactAgentService

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/agent/ReactAgentService.java`

**Goal:** Strip out all memory code. Inject `MemoryManager`. Change `processMessage` to accept `convId`. Keep ReAct loop unchanged.

- [ ] **Step 1: Replace ReactAgentService.java with the refactored version**

```java
package com.yizhaoqi.roboknow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.agent.tool.ToolRegistry;
import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.client.OpenAiClient;
import com.yizhaoqi.roboknow.memory.MemoryManager;
import com.yizhaoqi.roboknow.service.AgentStopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReactAgentService {

    private static final Logger logger = LoggerFactory.getLogger(ReactAgentService.class);
    private static final int MAX_ITERATIONS = 5;

    private static final Pattern THOUGHT_PATTERN =
        Pattern.compile("Thought:\\s*(.+?)(?=\\nAction:|\\nFinal Answer:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN =
        Pattern.compile("Action:\\s*(.+?)(?=\\nAction Input:|\\nThought:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN =
        Pattern.compile("Action Input:\\s*(.+?)(?=\\nObservation:|\\nThought:|\\nAction:|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN =
        Pattern.compile("Final Answer:\\s*(.+?)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final OpenAiClient openAiClient;
    private final ToolRegistry toolRegistry;
    private final AnswerGroundingService answerGroundingService;
    private final AgentStopService agentStopService;
    private final MemoryManager memoryManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReactAgentService(OpenAiClient openAiClient,
                              ToolRegistry toolRegistry,
                              AnswerGroundingService answerGroundingService,
                              AgentStopService agentStopService,
                              MemoryManager memoryManager) {
        this.openAiClient = openAiClient;
        this.toolRegistry = toolRegistry;
        this.answerGroundingService = answerGroundingService;
        this.agentStopService = agentStopService;
        this.memoryManager = memoryManager;
    }

    public void processMessage(String userId, String convId, String userMessage, WebSocketSession session) {
        logger.info("ReactAgent processing message, user: {}, convId: {}", userId, convId);
        try {
            List<Map<String, String>> contextMessages =
                    memoryManager.loadContext(userId, convId, userMessage);

            AgentContext ctx = new AgentContext(userId, userMessage, convId,
                    new ArrayList<>(), session);

            String finalAnswer = runReActLoop(ctx, contextMessages);

            sendCompletionNotification(session);
            memoryManager.record(userId, convId, userMessage, finalAnswer);
            logger.info("ReactAgent done, user: {}, convId: {}", userId, convId);
        } catch (Exception e) {
            logger.error("ReactAgent failed: {}", e.getMessage(), e);
            sendError(session, "The AI service is temporarily unavailable. Please try again later.");
        } finally {
            agentStopService.clear(session.getId());
        }
    }

    // ─────────────────────────────────────────────────────────
    // ReAct loop (unchanged from original)
    // ─────────────────────────────────────────────────────────

    private String runReActLoop(AgentContext ctx, List<Map<String, String>> contextMessages)
            throws InterruptedException {
        List<Map<String, String>> messages = buildInitialMessages(ctx, contextMessages);
        List<String> observations = new ArrayList<>();
        String finalAnswer = null;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (agentStopService.shouldStop(ctx.getSession().getId())) {
                logger.info("Stop signal detected, breaking at iteration {}", i);
                break;
            }

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.THINKING, i + 1));
            logger.debug("ReAct iteration {}: calling LLM", i + 1);

            String llmResponse = openAiClient.chatBlocking(
                messages,
                new AiUsageMetadata(ctx.getUserId(), ctx.getConversationId(), "react_step")
            );
            if (llmResponse.isBlank()) {
                logger.warn("LLM returned empty response at iteration {}", i + 1);
                break;
            }

            AgentStep step = parseResponse(llmResponse, i + 1);

            if (step.thought != null && !step.thought.isBlank()) {
                pushEvent(ctx.getSession(), AgentEvent.thought(step.thought));
            }

            if (step.isFinalAnswer) {
                if (observations.isEmpty()) {
                    logger.warn("LLM skipped tool call and gave direct Final Answer — forcing hybrid_search");
                    pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ACTING, i + 1));
                    pushEvent(ctx.getSession(), AgentEvent.action("hybrid_search", ctx.getUserMessage()));
                    pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.OBSERVING, i + 1));
                    String forcedObs = toolRegistry.execute("hybrid_search", ctx.getUserMessage(), ctx);
                    observations.add(forcedObs);
                    pushEvent(ctx.getSession(), AgentEvent.observation("hybrid_search", forcedObs));
                    messages.add(Map.of("role", "assistant", "content", step.formatAssistantContent()));
                    messages.add(Map.of("role", "user", "content", "Observation: " + forcedObs));
                    continue;
                }
                finalAnswer = step.finalAnswer;
                break;
            }

            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ACTING, i + 1));
            pushEvent(ctx.getSession(), AgentEvent.action(step.action, step.actionInput));
            pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.OBSERVING, i + 1));
            String observation = toolRegistry.execute(step.action, step.actionInput, ctx);
            observations.add(observation);
            pushEvent(ctx.getSession(), AgentEvent.observation(step.action, observation));
            logger.debug("Tool {} returned observation ({} chars)", step.action, observation.length());

            messages.add(Map.of("role", "assistant", "content", step.formatAssistantContent()));
            messages.add(Map.of("role", "user", "content", "Observation: " + observation));
        }

        if (finalAnswer == null) {
            finalAnswer = "No relevant information available. Repeated searches did not return enough knowledge-base evidence to answer this question.";
        }

        finalAnswer = answerGroundingService.groundAnswer(
            ctx.getUserMessage(), finalAnswer, observations,
            new AiUsageMetadata(ctx.getUserId(), ctx.getConversationId(), "answer_grounding")
        );

        pushEvent(ctx.getSession(), AgentEvent.stateChange(AgentState.ANSWERING, 0));
        streamText(ctx.getSession(), finalAnswer);
        return finalAnswer;
    }

    private List<Map<String, String>> buildInitialMessages(AgentContext ctx,
                                                             List<Map<String, String>> contextMessages) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.addAll(contextMessages); // LTM facts + STM summary + recent history
        messages.add(Map.of("role", "user", "content", ctx.getUserMessage()));
        return messages;
    }

    private String buildSystemPrompt() {
        return "You are an enterprise knowledge-base assistant. Use the available tools to answer the user's question.\n\n" +
               "**Available tools:**\n" +
               toolRegistry.getToolDescriptions() + "\n" +
               "**Response format (strictly follow this; answer in English):**\n\n" +
               "When a tool is needed:\n" +
               "Thought: [analyze the situation and decide the next action]\n" +
               "Action: [tool name, must be one of the tools listed above]\n" +
               "Action Input: [tool input]\n\n" +
               "When enough information is available:\n" +
               "Thought: [final reasoning]\n" +
               "Final Answer: [complete answer to the user in English]\n\n" +
               "**Rules:**\n" +
               "- Answer only in English.\n" +
               "- Use at most one tool per step.\n" +
               "- Always write Thought first, then Action or Final Answer.\n" +
               "- Tool results are returned as Observation: ...\n" +
               "- MANDATORY: You MUST call hybrid_search at least once before giving any Final Answer.\n" +
               "- Base the answer on retrieved knowledge-base content; do not fabricate information.\n" +
               "- Cite retrieved sources using the Source # markers from the observations.\n" +
               "- If searches find no relevant information, clearly say so in English.\n";
    }

    // ─────────────────────────────────────────────────────────
    // LLM response parsing (unchanged from original)
    // ─────────────────────────────────────────────────────────

    private AgentStep parseResponse(String response, int iteration) {
        AgentStep step = new AgentStep(iteration);

        Matcher faMatcher = FINAL_ANSWER_PATTERN.matcher(response);
        if (faMatcher.find()) {
            step.isFinalAnswer = true;
            step.finalAnswer = faMatcher.group(1).trim();
            Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
            if (thoughtMatcher.find()) step.thought = thoughtMatcher.group(1).trim();
            return step;
        }

        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(response);
        if (thoughtMatcher.find()) step.thought = thoughtMatcher.group(1).trim();

        Matcher actionMatcher = ACTION_PATTERN.matcher(response);
        if (actionMatcher.find()) step.action = actionMatcher.group(1).trim();

        Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(response);
        if (inputMatcher.find()) step.actionInput = inputMatcher.group(1).trim();

        if (step.action == null || step.actionInput == null || !toolRegistry.hasTool(step.action)) {
            logger.warn("No valid tool call parsed, treating LLM response as final answer at iteration {}", iteration);
            step.isFinalAnswer = true;
            step.finalAnswer = response.trim();
        }
        return step;
    }

    // ─────────────────────────────────────────────────────────
    // WebSocket push helpers (unchanged from original)
    // ─────────────────────────────────────────────────────────

    private void pushEvent(WebSocketSession session, AgentEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event.getPayload());
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            logger.error("Failed to push agent event: {}", e.getMessage(), e);
        }
    }

    private void streamText(WebSocketSession session, String text) throws InterruptedException {
        int chunkSize = 30;
        for (int i = 0; i < text.length(); i += chunkSize) {
            if (agentStopService.shouldStop(session.getId())) break;
            String chunk = text.substring(i, Math.min(i + chunkSize, text.length()));
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("chunk", chunk))));
            } catch (Exception e) {
                logger.error("Failed to stream text chunk: {}", e.getMessage(), e);
                break;
            }
            if (i + chunkSize < text.length()) Thread.sleep(25);
        }
    }

    private void sendCompletionNotification(WebSocketSession session) {
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "completion");
            notification.put("status", "finished");
            notification.put("message", "Response completed");
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("date", java.time.LocalDateTime.now().toString());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(notification)));
        } catch (Exception e) {
            logger.error("Failed to send completion notification: {}", e.getMessage(), e);
        }
    }

    private void sendError(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("error", message))));
        } catch (Exception e) {
            logger.error("Failed to send error message: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/agent/ReactAgentService.java
git commit -m "refactor(agent): remove inline memory code, delegate to MemoryManager"
```

---

## Task 12: Refactor ChatHandler + ChatWebSocketHandler

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/service/ChatHandler.java`
- Modify: `src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java`

- [ ] **Step 1: Update ChatHandler.java — add convId parameter**

Replace the `processMessage` method signature. The full updated file:

```java
package com.yizhaoqi.roboknow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.agent.ReactAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final ReactAgentService reactAgentService;
    private final AgentStopService agentStopService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHandler(ReactAgentService reactAgentService, AgentStopService agentStopService) {
        this.reactAgentService = reactAgentService;
        this.agentStopService = agentStopService;
    }

    public void processMessage(String userId, String convId, String userMessage, WebSocketSession session) {
        logger.info("ChatHandler 接收消息，用户: {}，convId: {}，会话: {}", userId, convId, session.getId());
        CompletableFuture.runAsync(() ->
            reactAgentService.processMessage(userId, convId, userMessage, session)
        ).exceptionally(ex -> {
            logger.error("ReactAgent 异步任务异常: {}", ex.getMessage(), ex);
            sendError(session, "处理消息时发生内部错误");
            return null;
        });
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

    private void sendError(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("error", message))));
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Update ChatWebSocketHandler.java — parse convId + call SessionManager**

Replace the full file:

```java
package com.yizhaoqi.roboknow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.service.ChatHandler;
import com.yizhaoqi.roboknow.service.SessionManager;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    private final ChatHandler chatHandler;
    private final SessionManager sessionManager;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatHandler chatHandler,
                                 SessionManager sessionManager,
                                 JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.sessionManager = sessionManager;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = extractUserId(session);
        sessions.put(userId, session);
        // Migrate old Redis key on first connection for existing users
        sessionManager.migrateOldKeyIfPresent(userId);
        logger.info("WebSocket connected userId={} sessionId={}", userId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        try {
            String payload = message.getPayload();

            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> json = objectMapper.readValue(payload, Map.class);
                    String type = (String) json.get("type");
                    String internalToken = (String) json.get("_internal_cmd_token");

                    // Stop command (internal)
                    if ("stop".equals(type) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        chatHandler.stopResponse(userId, session);
                        return;
                    }

                    // Chat message with optional convId
                    String userMessage = (String) json.get("message");
                    if (userMessage != null && !userMessage.isBlank()) {
                        String convId = resolveConvId(userId, (String) json.get("convId"));
                        chatHandler.processMessage(userId, convId, userMessage, session);
                        return;
                    }
                    // Fall through to plain text handling if no "message" key
                } catch (Exception parseError) {
                    logger.debug("JSON parse failed, treating as plain text: {}", parseError.getMessage());
                }
            }

            // Backward compat: plain text message
            if (!payload.isBlank()) {
                String convId = sessionManager.getActiveConvId(userId);
                chatHandler.processMessage(userId, convId, payload, session);
            }

        } catch (Exception e) {
            logger.error("Error handling message userId={}: {}", userId, e.getMessage(), e);
            sendError(session, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        sessions.remove(userId);
        logger.info("WebSocket closed userId={} status={}", userId, status);
    }

    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private String resolveConvId(String userId, String requestedConvId) {
        if (requestedConvId != null && !requestedConvId.isBlank()) {
            sessionManager.verifyOwnership(userId, requestedConvId);
            return requestedConvId;
        }
        return sessionManager.getActiveConvId(userId);
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String jwtToken = segments[segments.length - 1];
        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        if (username == null) {
            logger.warn("Cannot extract username from JWT, using token: {}", jwtToken);
            return jwtToken;
        }
        return username;
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        try {
            session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(Map.of("error", errorMessage))));
        } catch (Exception e) {
            logger.error("Failed to send error: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/service/ChatHandler.java \
        src/main/java/com/yizhaoqi/roboknow/handler/ChatWebSocketHandler.java
git commit -m "refactor(ws): thread convId through ChatHandler and WebSocketHandler"
```

---

## Task 13: Session REST endpoints in ConversationController

**Files:**
- Modify: `src/main/java/com/yizhaoqi/roboknow/controller/ConversationController.java`

Add these 4 session management endpoints to the existing `ConversationController`:
- `POST /api/v1/users/conversation/sessions` → create session
- `GET /api/v1/users/conversation/sessions` → list sessions
- `POST /api/v1/users/conversation/sessions/{convId}/switch` → switch active session
- `DELETE /api/v1/users/conversation/sessions/{convId}` → delete session

- [ ] **Step 1: Add SessionManager import and field to ConversationController**

Add at the top of the imports section:
```java
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.service.SessionManager;
```

Add field injection after the existing `@Autowired` fields:
```java
    @Autowired
    private SessionManager sessionManager;
```

- [ ] **Step 2: Add the 4 session endpoint methods**

Add these methods before the closing `}` of the class:

```java
    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(
            @RequestHeader("Authorization") String token) {
        String username = extractUsername(token);
        String convId = sessionManager.createSession(username);
        return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("convId", convId)));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(
            @RequestHeader("Authorization") String token) {
        String username = extractUsername(token);
        List<ConversationSession> sessions = sessionManager.listSessions(username);
        List<Map<String, Object>> data = sessions.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("convId", s.getId());
            m.put("title", s.getTitle());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("lastActiveAt", s.getLastActiveAt() != null ? s.getLastActiveAt().toString() : null);
            return m;
        }).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 200, "data", data));
    }

    @PostMapping("/sessions/{convId}/switch")
    public ResponseEntity<?> switchSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String convId) {
        String username = extractUsername(token);
        sessionManager.switchSession(username, convId);
        return ResponseEntity.ok(Map.of("code", 200, "message", "Switched to session " + convId));
    }

    @DeleteMapping("/sessions/{convId}")
    public ResponseEntity<?> deleteSession(
            @RequestHeader("Authorization") String token,
            @PathVariable String convId) {
        String username = extractUsername(token);
        sessionManager.deleteSession(username, convId);
        return ResponseEntity.ok(Map.of("code", 200, "message", "Session deleted"));
    }

    private String extractUsername(String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (username == null || username.isEmpty()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }
        return username;
    }
```

Also add these missing imports to ConversationController:
```java
import com.yizhaoqi.roboknow.exception.CustomException;
import java.util.stream.Collectors;
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/roboknow/controller/ConversationController.java
git commit -m "feat(api): add session CRUD endpoints to ConversationController"
```

---

## Task 14: Build verification

- [ ] **Step 1: Full clean compile**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Run tests**

```bash
mvn test
```
Expected: All existing tests pass (no new test failures introduced by refactoring)

- [ ] **Step 3: Start application and verify startup**

```bash
mvn spring-boot:run
```
Expected:
- Application starts on port 8081 without exceptions
- JPA creates `conversation_sessions` and `user_memory_facts` tables in MySQL
- AsyncConfig registers `memoryExecutor` thread pool
- No `NoSuchBeanDefinitionException` or `UnsatisfiedDependencyException` in logs

- [ ] **Step 4: Smoke test — new WebSocket chat**

Connect via WebSocket (e.g., with wscat or browser dev tools):
```
ws://localhost:8081/ws/chat/{jwt-token}
```
Send plain text message: `hello`
Expected: Agent responds, no NPE, conversation stored in Redis under `conversation:{uuid}`

- [ ] **Step 5: Smoke test — session list**
```bash
curl -H "Authorization: Bearer {token}" http://localhost:8081/api/v1/users/conversation/sessions
```
Expected: JSON response with `{"code": 200, "data": [...]}` containing at least 1 session

- [ ] **Step 6: Smoke test — create + switch session**
```bash
# Create new session
curl -X POST -H "Authorization: Bearer {token}" http://localhost:8081/api/v1/users/conversation/sessions
# Returns {"code":200,"data":{"convId":"uuid-xxx"}}

# Switch to new session
curl -X POST -H "Authorization: Bearer {token}" http://localhost:8081/api/v1/users/conversation/sessions/uuid-xxx/switch
# Returns {"code":200,"message":"Switched to session uuid-xxx"}
```

- [ ] **Step 7: Final commit**

```bash
git add -A
git commit -m "feat(memory): complete memory system refactor — multi-session, token-budget STM, async compression, LTM facts"
```

---

## Post-Implementation Notes

### Backward Compatibility
- Existing Redis `conversation:{id}` keys: still read/written correctly by `ConversationMemory`
- Existing Redis `user:{userId}:current_conversation` keys: migrated to `active_conversation` on first WebSocket connect (1-day TTL on old key)
- Existing `conversations` table: untouched, still written by old `ConversationService` if needed elsewhere
- Plain text WebSocket messages: still handled (backward compat path in `ChatWebSocketHandler`)

### Known Limitations (v1)
- `MemoryRetriever` uses in-memory scoring, not DB-level filtering — acceptable for low fact counts (<1000 per user)
- Token budget is per-conversation history only; system prompt + LTM tokens are not counted against the budget
- Idle session detection runs every 5 minutes; actual idle window = [30, 35] minutes

### Security Notes
- All session operations verify `userId` ownership before accessing convId
- Redis keys are scoped to `userId` or `convId` (which is UUID-based)
- LTM facts are private per userId — no orgTag sharing to prevent cross-tenant leakage
