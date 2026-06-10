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
     * If identical fact exists, touches updated_at only (no duplicate row).
     */
    @Transactional
    public void storeFact(String userId, String content, String sourceConvId) {
        if (content == null || content.isBlank()) return;
        String normalized = content.trim().toLowerCase();
        String hash = DigestUtils.md5Hex(normalized);

        Optional<UserMemoryFact> existing = factRepository.findByUserIdAndContentHash(userId, hash);
        if (existing.isPresent()) {
            factRepository.save(existing.get()); // touch updated_at via @UpdateTimestamp
            logger.debug("LTM fact already exists for userId={}, skipping duplicate", userId);
            return;
        }

        UserMemoryFact fact = new UserMemoryFact();
        fact.setUserId(userId);
        fact.setContent(content.trim());
        fact.setContentHash(hash);
        fact.setSourceConversationId(sourceConvId);
        factRepository.save(fact);
        logger.debug("LTM fact stored for userId={}: {}", userId,
                content.substring(0, Math.min(80, content.length())));
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
