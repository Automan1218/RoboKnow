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

    /**
     * Build context messages for the LLM (excludes system prompt — caller injects it).
     * Order: [LTM facts] [STM summary] [recent N messages]
     */
    public List<Map<String, String>> loadContext(String userId, String convId, String userMessage) {
        List<Map<String, String>> result = new ArrayList<>();

        // LTM: relevant facts with keyword+recency scoring
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
                logger.debug("Incremental fact extraction at round={} for convId={}",
                        session.getRoundCount(), convId);
                contextCompressor.extractFactsAsync(userId, convId);
            }
            sessionRepository.save(session);
        });
    }
}
