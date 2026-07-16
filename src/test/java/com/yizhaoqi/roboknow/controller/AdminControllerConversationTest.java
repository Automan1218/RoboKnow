package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.memory.ConversationMemory;
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.UserRepository;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminControllerConversationTest {

    private AdminController controller;
    private UserRepository userRepository;
    private JwtUtils jwtUtils;
    private ConversationSessionRepository sessionRepository;
    private ConversationMemory conversationMemory;

    @BeforeEach
    void setUp() {
        controller = new AdminController();
        userRepository = mock(UserRepository.class);
        jwtUtils = mock(JwtUtils.class);
        sessionRepository = mock(ConversationSessionRepository.class);
        conversationMemory = mock(ConversationMemory.class);

        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(controller, "conversationMemory", conversationMemory);

        when(jwtUtils.extractUsernameFromToken("valid")).thenReturn("admin");
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
    }

    @Test
    void getAllConversationsForSpecificUserReadsThroughConversationMemory() {
        User target = new User();
        target.setId(2L);
        target.setUsername("bob");
        target.setRole(User.Role.USER);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        ConversationSession session = new ConversationSession();
        session.setId("conv-1");
        session.setUserId("bob");
        session.setStatus(ConversationSession.Status.ACTIVE);
        when(sessionRepository.findByUserIdAndStatusOrderByLastActiveAtDesc("bob", ConversationSession.Status.ACTIVE))
                .thenReturn(List.of(session));
        when(conversationMemory.loadHistory("conv-1")).thenReturn(
                List.of(Map.of("role", "user", "content", "hi", "timestamp", "2026-07-01T10:00:00")));

        ResponseEntity<?> r = controller.getAllConversations("Bearer valid", "2", null, null);

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>)
                ((Map<String, Object>) r.getBody()).get("data");
        assertEquals(1, data.size());
        assertEquals("bob", data.get(0).get("username"));
        assertEquals("hi", data.get(0).get("content"));
    }

    @Test
    void getAllConversationsReturns404ForUnknownTargetUser() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> r = controller.getAllConversations("Bearer valid", "99", null, null);

        assertEquals(404, r.getStatusCode().value());
    }
}
