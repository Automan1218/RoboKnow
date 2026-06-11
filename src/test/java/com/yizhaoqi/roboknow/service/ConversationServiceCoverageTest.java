package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.model.Conversation;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.ConversationRepository;
import com.yizhaoqi.roboknow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConversationServiceCoverageTest {

    private ConversationService service;
    private ConversationRepository conversationRepository;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        service = new ConversationService();
        conversationRepository = mock(ConversationRepository.class);
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "conversationRepository", conversationRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
    }

    private User buildUser(Long id, String username, User.Role role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        return u;
    }

    private Conversation buildConversation(String summary) {
        Conversation c = new Conversation();
        c.setSummary(summary);
        return c;
    }

    // ── recordConversation ────────────────────────────────────────────────────

    @Test
    void recordConversationWithoutSummaryPersists() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        service.recordConversation("alice", "What is AI?", "AI is...");

        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    void recordConversationWithSummaryPersists() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        service.recordConversation("alice", "q", "a", "brief summary");

        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    void recordConversationThrowsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(CustomException.class,
                () -> service.recordConversation("ghost", "q", "a"));
    }

    // ── getRecentSummaries ────────────────────────────────────────────────────

    @Test
    void getRecentSummariesReturnsEmptyWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        List<String> result = service.getRecentSummaries("ghost", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void getRecentSummariesFiltersNullAndBlankSummaries() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        List<Conversation> convs = List.of(
                buildConversation("valid summary"),
                buildConversation(null),
                buildConversation("   ")
        );
        when(conversationRepository.findRecentWithSummaryByUserId(eq(1L), any(PageRequest.class)))
                .thenReturn(convs);

        List<String> result = service.getRecentSummaries("alice", 5);
        assertEquals(1, result.size());
        assertEquals("valid summary", result.get(0));
    }

    @Test
    void getRecentSummariesReturnsMultipleValidSummaries() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        List<Conversation> convs = List.of(
                buildConversation("summary 1"),
                buildConversation("summary 2")
        );
        when(conversationRepository.findRecentWithSummaryByUserId(eq(1L), any(PageRequest.class)))
                .thenReturn(convs);

        List<String> result = service.getRecentSummaries("alice", 5);
        assertEquals(2, result.size());
    }

    // ── getConversations ──────────────────────────────────────────────────────

    @Test
    void getConversationsForUserWithoutDateRange() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(conversationRepository.findByUserId(1L)).thenReturn(List.of());

        List<Conversation> result = service.getConversations("alice", null, null);
        assertTrue(result.isEmpty());
        verify(conversationRepository).findByUserId(1L);
    }

    @Test
    void getConversationsForUserWithDateRange() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 1, 0, 0);
        when(conversationRepository.findByUserIdAndTimestampBetween(1L, start, end))
                .thenReturn(List.of());

        List<Conversation> result = service.getConversations("alice", start, end);
        assertTrue(result.isEmpty());
        verify(conversationRepository).findByUserIdAndTimestampBetween(1L, start, end);
    }

    @Test
    void getConversationsThrowsWhenUserNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(CustomException.class,
                () -> service.getConversations("ghost", null, null));
    }

    // ── getAllConversations ────────────────────────────────────────────────────

    @Test
    void getAllConversationsThrowsWhenRequesterIsNotAdmin() {
        User user = buildUser(1L, "alice", User.Role.USER);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThrows(CustomException.class,
                () -> service.getAllConversations("alice", null, null, null));
    }

    @Test
    void getAllConversationsThrowsWhenAdminNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(CustomException.class,
                () -> service.getAllConversations("ghost", null, null, null));
    }

    @Test
    void getAllConversationsForAdminWithoutFiltersReturnsAll() {
        User admin = buildUser(1L, "admin", User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(conversationRepository.findAll()).thenReturn(List.of());

        List<Conversation> result = service.getAllConversations("admin", null, null, null);
        assertTrue(result.isEmpty());
        verify(conversationRepository).findAll();
    }

    @Test
    void getAllConversationsForAdminWithDateRange() {
        User admin = buildUser(1L, "admin", User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 1, 0, 0);
        when(conversationRepository.findByTimestampBetween(start, end)).thenReturn(List.of());

        List<Conversation> result = service.getAllConversations("admin", null, start, end);
        assertTrue(result.isEmpty());
        verify(conversationRepository).findByTimestampBetween(start, end);
    }

    @Test
    void getAllConversationsForAdminWithTargetUser() {
        User admin = buildUser(1L, "admin", User.Role.ADMIN);
        User target = buildUser(2L, "bob", User.Role.USER);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(target));
        when(conversationRepository.findByUserId(2L)).thenReturn(List.of());

        List<Conversation> result = service.getAllConversations("admin", "bob", null, null);
        assertTrue(result.isEmpty());
        verify(conversationRepository).findByUserId(2L);
    }

    @Test
    void getAllConversationsForAdminWithTargetUserAndDateRange() {
        User admin = buildUser(1L, "admin", User.Role.ADMIN);
        User target = buildUser(2L, "bob", User.Role.USER);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(target));
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 6, 1, 0, 0);
        when(conversationRepository.findByUserIdAndTimestampBetween(2L, start, end))
                .thenReturn(List.of());

        List<Conversation> result = service.getAllConversations("admin", "bob", start, end);
        assertTrue(result.isEmpty());
        verify(conversationRepository).findByUserIdAndTimestampBetween(2L, start, end);
    }

    @Test
    void getAllConversationsThrowsWhenTargetUserNotFound() {
        User admin = buildUser(1L, "admin", User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(CustomException.class,
                () -> service.getAllConversations("admin", "ghost", null, null));
    }
}
