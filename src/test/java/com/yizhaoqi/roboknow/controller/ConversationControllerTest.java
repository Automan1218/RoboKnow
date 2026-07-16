package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.UserRepository;
import com.yizhaoqi.roboknow.service.SessionManager;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConversationControllerTest {

    private ConversationController controller;
    private SessionManager sessionManager;
    private JwtUtils jwtUtils;
    private UserRepository userRepository;
    private ValueOperations<String, String> ops;
    private com.yizhaoqi.roboknow.memory.ConversationMemory conversationMemory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new ConversationController();
        sessionManager = mock(SessionManager.class);
        jwtUtils = mock(JwtUtils.class);
        userRepository = mock(UserRepository.class);
        conversationMemory = mock(com.yizhaoqi.roboknow.memory.ConversationMemory.class);

        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null); // default: no Redis key

        ReflectionTestUtils.setField(controller, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(controller, "conversationMemory", conversationMemory);
    }

    private void stubValidToken(String username) {
        when(jwtUtils.extractUsernameFromToken("valid")).thenReturn(username);
    }

    private User buildUser(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(User.Role.USER);
        return u;
    }

    // ── getConversations ──────────────────────────────────────────────────────

    @Test
    void getConversationsReturns401ForInvalidToken() {
        when(jwtUtils.extractUsernameFromToken("bad")).thenReturn(null);
        ResponseEntity<?> r = controller.getConversations("Bearer bad", null, null);
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void getConversationsReturns404WhenUserNotFound() {
        stubValidToken("ghost");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(404, r.getStatusCode().value());
    }

    @Test
    void getConversationsReturnsEmptyWhenNoRedisKey() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        // ops returns null for all keys (default in setUp)

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
        assertData(r, List.class);
        assertTrue(((List<?>) extractData(r)).isEmpty());
    }

    @Test
    void getConversationsReturnsMessagesFromConversationMemory() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn("conv-abc");
        when(conversationMemory.loadHistory("conv-abc")).thenReturn(
                List.of(Map.of("role", "user", "content", "hello", "timestamp", "2026-06-11T10:00:00")));

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
        List<?> data = (List<?>) extractData(r);
        assertEquals(1, data.size());
    }

    @Test
    void getConversationsFiltersMessagesByDateRange() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn("conv-abc");
        when(conversationMemory.loadHistory("conv-abc")).thenReturn(List.of(
                Map.of("role", "user", "content", "old", "timestamp", "2026-01-01T10:00:00"),
                Map.of("role", "user", "content", "new", "timestamp", "2026-06-11T10:00:00")));

        ResponseEntity<?> r = controller.getConversations(
                "Bearer valid", "2026-06-01", "2026-06-30");
        assertEquals(200, r.getStatusCode().value());
        List<?> data = (List<?>) extractData(r);
        assertEquals(1, data.size()); // only the "new" message survives
    }

    @Test
    void getConversationsUsesLegacyRedisKeyWhenActiveKeyMissing() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn(null);
        when(ops.get("user:1:current_conversation")).thenReturn("conv-legacy");
        when(conversationMemory.loadHistory("conv-legacy")).thenReturn(List.of());

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
    }

    @Test
    void getConversationsReturnsEmptyWhenHistoryIsEmpty() {
        stubValidToken("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(buildUser(1L, "alice")));
        when(ops.get("user:1:active_conversation")).thenReturn("conv-abc");
        when(conversationMemory.loadHistory("conv-abc")).thenReturn(List.of());

        ResponseEntity<?> r = controller.getConversations("Bearer valid", null, null);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(((List<?>) extractData(r)).isEmpty());
    }

    // ── session CRUD ──────────────────────────────────────────────────────────

    @Test
    void createSessionReturnsConvId() {
        stubValidToken("alice");
        when(sessionManager.createSession("alice")).thenReturn("conv-123");

        ResponseEntity<?> r = controller.createSession("Bearer valid");
        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) extractData(r);
        assertEquals("conv-123", data.get("convId"));
    }

    @Test
    void createSessionThrowsForInvalidToken() {
        when(jwtUtils.extractUsernameFromToken("bad")).thenReturn(null);
        // extractUsername throws CustomException for null username
        assertThrows(Exception.class, () -> controller.createSession("Bearer bad"));
    }

    @Test
    void listSessionsReturnsMappedSessions() {
        stubValidToken("alice");
        ConversationSession s = new ConversationSession();
        s.setId("conv-1");
        s.setTitle("Chat A");
        when(sessionManager.listSessions("alice")).thenReturn(List.of(s));

        ResponseEntity<?> r = controller.listSessions("Bearer valid");
        assertEquals(200, r.getStatusCode().value());
        List<?> data = (List<?>) extractData(r);
        assertEquals(1, data.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) data.get(0);
        assertEquals("conv-1", entry.get("convId"));
        assertEquals("Chat A", entry.get("title"));
    }

    @Test
    void switchSessionDelegatesToSessionManager() {
        stubValidToken("alice");
        doNothing().when(sessionManager).switchSession("alice", "conv-1");

        ResponseEntity<?> r = controller.switchSession("Bearer valid", "conv-1");
        assertEquals(200, r.getStatusCode().value());
        verify(sessionManager).switchSession("alice", "conv-1");
    }

    @Test
    void deleteSessionDelegatesToSessionManager() {
        stubValidToken("alice");
        doNothing().when(sessionManager).deleteSession("alice", "conv-1");

        ResponseEntity<?> r = controller.deleteSession("Bearer valid", "conv-1");
        assertEquals(200, r.getStatusCode().value());
        verify(sessionManager).deleteSession("alice", "conv-1");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Object extractData(ResponseEntity<?> r) {
        return ((Map<String, Object>) r.getBody()).get("data");
    }

    @SuppressWarnings("unchecked")
    private static void assertData(ResponseEntity<?> r, Class<?> type) {
        Object data = ((Map<String, Object>) r.getBody()).get("data");
        assertTrue(type.isInstance(data));
    }
}
