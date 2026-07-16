package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MemoryManagerTest {

    private ConversationMemory conversationMemory;
    private LongTermMemory longTermMemory;
    private MemoryRetriever memoryRetriever;
    private ContextCompressor contextCompressor;
    private ConversationSessionRepository sessionRepository;
    private MessagePersistenceService messagePersistenceService;
    private MemoryManager manager;

    @BeforeEach
    void setUp() {
        conversationMemory = mock(ConversationMemory.class);
        longTermMemory = mock(LongTermMemory.class);
        memoryRetriever = mock(MemoryRetriever.class);
        contextCompressor = mock(ContextCompressor.class);
        sessionRepository = mock(ConversationSessionRepository.class);
        messagePersistenceService = mock(MessagePersistenceService.class);
        manager = new MemoryManager(conversationMemory, longTermMemory, memoryRetriever,
                contextCompressor, sessionRepository, messagePersistenceService);

        when(conversationMemory.appendAndEvictIfNeeded(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        when(sessionRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void recordTriggersDurableWrite() {
        manager.record("alice", "conv-1", "question", "answer");

        verify(messagePersistenceService).saveAsync("conv-1", "question", "answer");
    }

    @Test
    void recordStillAppendsToRedisFirst() {
        manager.record("alice", "conv-1", "question", "answer");

        verify(conversationMemory).appendAndEvictIfNeeded("conv-1", "question", "answer");
    }
}
