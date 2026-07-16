package com.yizhaoqi.roboknow.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationMemoryTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private TokenBudget tokenBudget;
    private MessagePersistenceService messagePersistenceService;
    private ConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        tokenBudget = mock(TokenBudget.class);
        messagePersistenceService = mock(MessagePersistenceService.class);
        memory = new ConversationMemory(redis, tokenBudget,
                new com.fasterxml.jackson.databind.ObjectMapper(), messagePersistenceService);
    }

    @Test
    void loadHistoryReturnsRedisDataWithoutTouchingDb() {
        when(ops.get("conversation:conv-1"))
                .thenReturn("[{\"role\":\"user\",\"content\":\"hi\",\"timestamp\":\"2026-07-01T10:00:00\"}]");

        List<Map<String, String>> result = memory.loadHistory("conv-1");

        assertEquals(1, result.size());
        verifyNoInteractions(messagePersistenceService);
    }

    @Test
    void loadHistoryFallsBackToDbOnRedisMissAndBackfillsRedis() {
        when(ops.get("conversation:conv-1")).thenReturn(null);
        List<Map<String, String>> dbHistory = List.of(
                Map.of("role", "user", "content", "hi", "timestamp", "2026-07-01T10:00:00"));
        when(messagePersistenceService.loadFromDb("conv-1")).thenReturn(dbHistory);

        List<Map<String, String>> result = memory.loadHistory("conv-1");

        assertEquals(1, result.size());
        assertEquals("hi", result.get(0).get("content"));
        verify(ops).set(eq("conversation:conv-1"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void loadHistoryReturnsEmptyWhenBothRedisAndDbMiss() {
        when(ops.get("conversation:conv-1")).thenReturn(null);
        when(messagePersistenceService.loadFromDb("conv-1")).thenReturn(List.of());

        List<Map<String, String>> result = memory.loadHistory("conv-1");

        assertTrue(result.isEmpty());
        verify(ops, never()).set(eq("conversation:conv-1"), anyString(), any(java.time.Duration.class));
    }
}
