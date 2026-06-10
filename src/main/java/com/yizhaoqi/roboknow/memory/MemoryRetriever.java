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
                .filter(sf -> sf.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredFact::score).reversed())
                .limit(topK)
                .map(ScoredFact::fact)
                .collect(Collectors.toList());
    }

    private double score(UserMemoryFact fact, Set<String> queryWords, LocalDateTime now) {
        Set<String> factWords = tokenize(fact.getContent());
        long matchCount = queryWords.stream().filter(factWords::contains).count();
        double keywordScore = queryWords.isEmpty() ? 0 : (double) matchCount / queryWords.size();

        long daysSince = ChronoUnit.DAYS.between(
                fact.getCreatedAt() != null ? fact.getCreatedAt() : now, now);
        double recencyScore = 1.0 / (1.0 + Math.abs(daysSince));

        double hitBonus = Math.min(0.2, fact.getHitCount() * 0.02);

        return 0.6 * keywordScore + 0.3 * recencyScore + 0.1 * hitBonus;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}，。！？、：；“”‘’]+"))
                .filter(w -> w.length() > 1 && !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    private record ScoredFact(UserMemoryFact fact, double score) {}
}
