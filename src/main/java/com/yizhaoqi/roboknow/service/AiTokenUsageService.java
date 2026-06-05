package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.model.AiTokenUsage;
import com.yizhaoqi.roboknow.repository.AiTokenUsageRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiTokenUsageService {

    private final AiTokenUsageRepository repository;

    public AiTokenUsageService(AiTokenUsageRepository repository) {
        this.repository = repository;
    }

    public void recordUsage(AiUsageMetadata metadata, String model, Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }

        AiUsageMetadata resolvedMetadata = metadata != null ? metadata : AiUsageMetadata.system("chat_blocking");
        AiTokenUsage tokenUsage = new AiTokenUsage();
        tokenUsage.setUsername(blankToDefault(resolvedMetadata.username(), "system"));
        tokenUsage.setConversationId(resolvedMetadata.conversationId());
        tokenUsage.setOperation(blankToDefault(resolvedMetadata.operation(), "chat_blocking"));
        tokenUsage.setModel(blankToDefault(model, "unknown"));
        tokenUsage.setPromptTokens(asInt(usage.get("prompt_tokens")));
        tokenUsage.setCompletionTokens(asInt(usage.get("completion_tokens")));
        tokenUsage.setTotalTokens(asInt(usage.get("total_tokens")));

        repository.save(tokenUsage);
    }

    public Map<String, Object> getUserUsage(String username, LocalDateTime start, LocalDateTime end) {
        LocalDateTime startTime = start != null ? start : LocalDateTime.now().minusDays(30);
        LocalDateTime endTime = end != null ? end : LocalDateTime.now();
        List<AiTokenUsage> records = repository.findByUsernameAndCreatedAtBetweenOrderByCreatedAtDesc(
            username,
            startTime,
            endTime
        );

        long promptTokens = records.stream().mapToLong(r -> safe(r.getPromptTokens())).sum();
        long completionTokens = records.stream().mapToLong(r -> safe(r.getCompletionTokens())).sum();
        long totalTokens = records.stream().mapToLong(r -> safe(r.getTotalTokens())).sum();

        Map<String, Object> summary = new HashMap<>();
        summary.put("promptTokens", promptTokens);
        summary.put("completionTokens", completionTokens);
        summary.put("totalTokens", totalTokens);
        summary.put("requestCount", records.size());
        summary.put("start", startTime);
        summary.put("end", endTime);

        List<Map<String, Object>> rows = records.stream().map(this::toMap).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("summary", summary);
        response.put("records", rows);
        return response;
    }

    private Map<String, Object> toMap(AiTokenUsage usage) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", usage.getId());
        row.put("username", usage.getUsername());
        row.put("conversationId", usage.getConversationId());
        row.put("model", usage.getModel());
        row.put("operation", usage.getOperation());
        row.put("promptTokens", usage.getPromptTokens());
        row.put("completionTokens", usage.getCompletionTokens());
        row.put("totalTokens", usage.getTotalTokens());
        row.put("createdAt", usage.getCreatedAt());
        return row;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
