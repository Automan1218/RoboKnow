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
