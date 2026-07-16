package com.yizhaoqi.roboknow.memory;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagePersistenceServiceTest {

    private ConversationMessageRepository repository;
    private MessagePersistenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationMessageRepository.class);
        service = new MessagePersistenceService(repository);
    }

    @Test
    void saveAsyncPersistsUserThenAssistantWithIncrementingSeq() {
        when(repository.countByConvId("conv-1")).thenReturn(4L);

        service.saveAsync("conv-1", "question", "answer");

        ArgumentCaptor<ConversationMessage> captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(repository, times(2)).save(captor.capture());

        List<ConversationMessage> saved = captor.getAllValues();
        assertEquals("user", saved.get(0).getRole());
        assertEquals("question", saved.get(0).getContent());
        assertEquals(4, saved.get(0).getSeq());
        assertEquals("assistant", saved.get(1).getRole());
        assertEquals("answer", saved.get(1).getContent());
        assertEquals(5, saved.get(1).getSeq());
        assertEquals("conv-1", saved.get(0).getConvId());
    }

    @Test
    void saveAsyncSwallowsRepositoryExceptions() {
        when(repository.countByConvId(anyString())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.saveAsync("conv-1", "q", "a"));
    }

    @Test
    void loadFromDbReturnsEmptyListWhenNoRows() {
        when(repository.findByConvIdOrderBySeqAsc("conv-1")).thenReturn(List.of());

        List<Map<String, String>> result = service.loadFromDb("conv-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void loadFromDbMapsRowsToRoleContentTimestamp() {
        ConversationMessage m = new ConversationMessage();
        m.setRole("user");
        m.setContent("hello");
        m.setCreatedAt(LocalDateTime.of(2026, 7, 1, 10, 30, 0));
        when(repository.findByConvIdOrderBySeqAsc("conv-1")).thenReturn(List.of(m));

        List<Map<String, String>> result = service.loadFromDb("conv-1");

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).get("role"));
        assertEquals("hello", result.get(0).get("content"));
        assertEquals("2026-07-01T10:30:00", result.get(0).get("timestamp"));
    }

    @Test
    void loadFromDbSwallowsRepositoryExceptionsAndReturnsEmpty() {
        when(repository.findByConvIdOrderBySeqAsc(anyString())).thenThrow(new RuntimeException("db down"));

        List<Map<String, String>> result = service.loadFromDb("conv-1");

        assertTrue(result.isEmpty());
    }
}
