package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.model.AiTokenUsage;
import com.yizhaoqi.roboknow.repository.AiTokenUsageRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTokenUsageServiceTest {

    @Test
    void recordUsagePersistsTokenMetrics() {
        AiTokenUsageRepository repository = mock(AiTokenUsageRepository.class);
        AiTokenUsageService service = new AiTokenUsageService(repository);

        service.recordUsage(
            new AiUsageMetadata("admin", "conversation-1", "react_step"),
            "gpt-test",
            Map.of("prompt_tokens", 11, "completion_tokens", 7, "total_tokens", 18)
        );

        verify(repository).save(any(AiTokenUsage.class));
    }

    @Test
    void getUserUsageReturnsSummaryAndRecords() {
        AiTokenUsageRepository repository = mock(AiTokenUsageRepository.class);
        AiTokenUsageService service = new AiTokenUsageService(repository);
        LocalDateTime start = LocalDateTime.parse("2026-06-01T00:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-06-05T23:59:59");

        AiTokenUsage first = usage("admin", "react_step", 10, 5, 15);
        AiTokenUsage second = usage("admin", "answer_grounding", 20, 8, 28);
        when(repository.findByUsernameAndCreatedAtBetweenOrderByCreatedAtDesc("admin", start, end))
            .thenReturn(List.of(second, first));

        Map<String, Object> response = service.getUserUsage("admin", start, end);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("summary");
        assertEquals(30L, summary.get("promptTokens"));
        assertEquals(13L, summary.get("completionTokens"));
        assertEquals(43L, summary.get("totalTokens"));
        assertEquals(2, summary.get("requestCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) response.get("records");
        assertFalse(records.isEmpty());
        assertEquals("answer_grounding", records.get(0).get("operation"));
    }

    private AiTokenUsage usage(String username, String operation, int prompt, int completion, int total) {
        AiTokenUsage usage = new AiTokenUsage();
        usage.setUsername(username);
        usage.setOperation(operation);
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        usage.setTotalTokens(total);
        usage.setModel("gpt-test");
        usage.setConversationId("conversation-1");
        usage.setCreatedAt(LocalDateTime.parse("2026-06-04T12:00:00"));
        return usage;
    }
}
