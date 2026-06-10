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
     * Called when eviction happened. Must NOT block the request path.
     */
    @Async("memoryExecutor")
    public void compressAsync(String convId, String existingSummary) {
        List<Map<String, String>> pending = conversationMemory.loadPendingCompress(convId);
        if (pending.isEmpty()) return;

        try {
            // Map: compress 5-message chunks
            List<String> partialSummaries = new ArrayList<>();
            for (int i = 0; i < pending.size(); i += MAP_CHUNK_SIZE) {
                List<Map<String, String>> chunk =
                        pending.subList(i, Math.min(i + MAP_CHUNK_SIZE, pending.size()));
                String partial = compressChunk(chunk, convId);
                if (partial != null && !partial.isBlank()) {
                    partialSummaries.add(partial);
                }
            }

            if (partialSummaries.isEmpty()) return;

            // Reduce: merge all partial summaries (+ existing if present)
            String finalSummary;
            if (partialSummaries.size() == 1 && (existingSummary == null || existingSummary.isBlank())) {
                finalSummary = partialSummaries.get(0);
            } else {
                finalSummary = reduceSummaries(existingSummary, partialSummaries, convId);
            }

            if (finalSummary != null && !finalSummary.isBlank()) {
                conversationMemory.saveSummary(convId, finalSummary);
                conversationMemory.clearPendingCompress(convId);
                logger.info("STM compression done for convId={}, {} messages compressed",
                        convId, pending.size());
            }
        } catch (Exception e) {
            logger.warn("STM compression failed for convId={}: {} — pending_compress retained for retry",
                    convId, e.getMessage());
        }
    }

    /**
     * Async fact extraction from a conversation's current history.
     * Triggered on incremental count or idle timeout.
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

            for (String line : result.split("\n")) {
                String fact = line.replaceFirst("^\\d+\\.\\s*", "").trim();
                if (!fact.isBlank() && fact.length() > 10) {
                    longTermMemory.storeFact(userId, fact, convId);
                }
            }
            logger.info("Fact extraction done for userId={} convId={}", userId, convId);
        } catch (Exception e) {
            logger.warn("Fact extraction failed for userId={} convId={}: {}",
                    userId, convId, e.getMessage());
        }
    }

    /**
     * Scheduled scan: trigger fact extraction for sessions idle > idleTimeoutMinutes.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelay = 300_000)
    public void processIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(idleTimeoutMinutes);
        try {
            sessionRepository.findIdleSessions(cutoff).forEach(session -> {
                logger.debug("Idle session userId={} convId={}, triggering fact extraction",
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
