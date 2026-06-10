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
     * Atomically append user+assistant message pair.
     * Returns messages evicted to pending_compress (empty if budget not exceeded).
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
