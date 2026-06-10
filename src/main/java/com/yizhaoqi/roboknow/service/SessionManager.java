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
import java.util.Map;
import java.util.UUID;

@Service
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final Duration ACTIVE_CONV_TTL = Duration.ofDays(30);

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
        migrateOldKeyIfPresent(userId);

        String cached = redis.opsForValue().get(activeKey(userId));
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        return sessionRepository
                .findTopByUserIdAndStatusOrderByLastActiveAtDesc(userId, ConversationSession.Status.ACTIVE)
                .map(session -> {
                    setActiveConvId(userId, session.getId());
                    return session.getId();
                })
                .orElseGet(() -> createSession(userId));
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
     * Generate a session title asynchronously after the first exchange.
     */
    @Async("memoryExecutor")
    public void generateTitleAsync(String convId, String firstUserMessage) {
        try {
            String prompt = firstUserMessage.length() > 200
                    ? firstUserMessage.substring(0, 200) : firstUserMessage;
            String title = openAiClient.chatBlocking(
                List.of(
                    Map.of("role", "system", "content",
                        "Generate a short 4-8 word title for a conversation that starts with the " +
                        "following message. Reply with ONLY the title, no punctuation at the end."),
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
     * One-time migration: copy user:{userId}:current_conversation → active_conversation.
     * Sets old key to expire in 1 day (natural death).
     */
    public void migrateOldKeyIfPresent(String userId) {
        String oldKey = "user:" + userId + ":current_conversation";
        String oldValue = redis.opsForValue().get(oldKey);
        if (oldValue != null && !oldValue.isBlank()) {
            String newKey = activeKey(userId);
            if (redis.opsForValue().get(newKey) == null) {
                redis.opsForValue().set(newKey, oldValue, ACTIVE_CONV_TTL);
                logger.info("Migrated old Redis key for userId={}", userId);
            }
            redis.expire(oldKey, Duration.ofDays(1));
        }
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private void setActiveConvId(String userId, String convId) {
        redis.opsForValue().set(activeKey(userId), convId, ACTIVE_CONV_TTL);
    }

    private String activeKey(String userId) {
        return "user:" + userId + ":active_conversation";
    }
}
