package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.model.User;
import com.yizhaoqi.roboknow.repository.UserRepository;
import com.yizhaoqi.roboknow.service.UserService;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserControllerTest {

    private UserController controller;
    private UserService userService;
    private JwtUtils jwtUtils;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        userService = mock(UserService.class);
        jwtUtils = mock(JwtUtils.class);
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    void registerRejectsNullUsername() {
        ResponseEntity<?> r = controller.register(new UserRequest(null, "pass"));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void registerRejectsEmptyUsername() {
        ResponseEntity<?> r = controller.register(new UserRequest("", "pass"));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void registerRejectsNullPassword() {
        ResponseEntity<?> r = controller.register(new UserRequest("alice", null));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void registerRejectsEmptyPassword() {
        ResponseEntity<?> r = controller.register(new UserRequest("alice", ""));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void registerSucceeds() {
        doNothing().when(userService).registerUser("alice", "pass");
        ResponseEntity<?> r = controller.register(new UserRequest("alice", "pass"));
        assertEquals(200, r.getStatusCode().value());
        assertBodyCode(r, 200);
    }

    @Test
    void registerPropagatesCustomException() {
        doThrow(new CustomException("Username taken", HttpStatus.CONFLICT))
                .when(userService).registerUser("alice", "pass");
        ResponseEntity<?> r = controller.register(new UserRequest("alice", "pass"));
        assertEquals(409, r.getStatusCode().value());
    }

    @Test
    void registerPropagatesUnexpectedException() {
        doThrow(new RuntimeException("DB error")).when(userService).registerUser("alice", "pass");
        ResponseEntity<?> r = controller.register(new UserRequest("alice", "pass"));
        assertEquals(500, r.getStatusCode().value());
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    void loginRejectsEmptyUsername() {
        ResponseEntity<?> r = controller.login(new UserRequest("", "pass"));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void loginRejectsNullPassword() {
        ResponseEntity<?> r = controller.login(new UserRequest("alice", null));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void loginRejectsInvalidCredentials() {
        when(userService.authenticateUser("alice", "wrong")).thenReturn(null);
        ResponseEntity<?> r = controller.login(new UserRequest("alice", "wrong"));
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void loginSucceeds() {
        when(userService.authenticateUser("alice", "pass")).thenReturn("alice");
        when(jwtUtils.generateToken("alice")).thenReturn("tok");
        when(jwtUtils.generateRefreshToken("alice")).thenReturn("refresh");

        ResponseEntity<?> r = controller.login(new UserRequest("alice", "pass"));
        assertEquals(200, r.getStatusCode().value());
        assertBodyCode(r, 200);
    }

    @Test
    void loginPropagatesCustomException() {
        doThrow(new CustomException("Locked", HttpStatus.FORBIDDEN))
                .when(userService).authenticateUser("alice", "pass");
        ResponseEntity<?> r = controller.login(new UserRequest("alice", "pass"));
        assertEquals(403, r.getStatusCode().value());
    }

    @Test
    void loginPropagatesUnexpectedException() {
        when(userService.authenticateUser("alice", "pass")).thenThrow(new RuntimeException("fail"));
        ResponseEntity<?> r = controller.login(new UserRequest("alice", "pass"));
        assertEquals(500, r.getStatusCode().value());
    }

    // ── getCurrentUser ────────────────────────────────────────────────────────

    @Test
    void getCurrentUserRejectsInvalidToken() {
        when(jwtUtils.extractUsernameFromToken("bad")).thenReturn(null);
        ResponseEntity<?> r = controller.getCurrentUser("Bearer bad");
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void getCurrentUserReturnsUserDataWithOrgTags() {
        User user = buildUser(1L, "alice", User.Role.USER, "ORG1", "ORG1");
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        ResponseEntity<?> r = controller.getCurrentUser("Bearer tok");
        assertEquals(200, r.getStatusCode().value());
        assertBodyCode(r, 200);
    }

    @Test
    void getCurrentUserReturnsUserDataWithNullOrgTags() {
        User user = buildUser(2L, "bob", User.Role.USER, null, null);
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("bob");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));

        ResponseEntity<?> r = controller.getCurrentUser("Bearer tok");
        assertEquals(200, r.getStatusCode().value());
    }

    @Test
    void getCurrentUserThrowsWhenUserNotFound() {
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("ghost");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        ResponseEntity<?> r = controller.getCurrentUser("Bearer tok");
        assertEquals(404, r.getStatusCode().value());
    }

    @Test
    void getCurrentUserHandlesUnexpectedException() {
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenThrow(new RuntimeException("DB error"));

        ResponseEntity<?> r = controller.getCurrentUser("Bearer tok");
        assertEquals(500, r.getStatusCode().value());
    }

    // ── getUserOrgTags ────────────────────────────────────────────────────────

    @Test
    void getUserOrgTagsRejectsInvalidToken() {
        when(jwtUtils.extractUsernameFromToken("bad")).thenReturn(null);
        ResponseEntity<?> r = controller.getUserOrgTags("Bearer bad");
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void getUserOrgTagsSucceeds() {
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        when(userService.getUserOrgTags("alice")).thenReturn(Map.of("orgTags", "ORG1"));

        ResponseEntity<?> r = controller.getUserOrgTags("Bearer tok");
        assertEquals(200, r.getStatusCode().value());
    }

    // ── setPrimaryOrg ──────────────────────────────────────────────────────────

    @Test
    void setPrimaryOrgRejectsInvalidToken() {
        when(jwtUtils.extractUsernameFromToken("bad")).thenReturn(null);
        ResponseEntity<?> r = controller.setPrimaryOrg("Bearer bad", new PrimaryOrgRequest("ORG1"));
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void setPrimaryOrgRejectsEmptyOrg() {
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        ResponseEntity<?> r = controller.setPrimaryOrg("Bearer tok", new PrimaryOrgRequest(""));
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void setPrimaryOrgSucceeds() {
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        doNothing().when(userService).setUserPrimaryOrg("alice", "ORG1");

        ResponseEntity<?> r = controller.setPrimaryOrg("Bearer tok", new PrimaryOrgRequest("ORG1"));
        assertEquals(200, r.getStatusCode().value());
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logoutRejectsMissingBearerPrefix() {
        ResponseEntity<?> r = controller.logout("no-prefix");
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void logoutRejectsNullToken() {
        ResponseEntity<?> r = controller.logout(null);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void logoutReturns401WhenUsernameCannotBeExtracted() {
        when(jwtUtils.extractUsernameFromToken("jwt")).thenReturn(null);
        ResponseEntity<?> r = controller.logout("Bearer jwt");
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void logoutSucceeds() {
        when(jwtUtils.extractUsernameFromToken("jwt")).thenReturn("alice");
        doNothing().when(jwtUtils).invalidateToken("jwt");

        ResponseEntity<?> r = controller.logout("Bearer jwt");
        assertEquals(200, r.getStatusCode().value());
        assertBodyCode(r, 200);
    }

    @Test
    void logoutHandlesException() {
        when(jwtUtils.extractUsernameFromToken("jwt")).thenReturn("alice");
        doThrow(new RuntimeException("fail")).when(jwtUtils).invalidateToken("jwt");

        ResponseEntity<?> r = controller.logout("Bearer jwt");
        assertEquals(500, r.getStatusCode().value());
    }

    // ── logoutAll ─────────────────────────────────────────────────────────────

    @Test
    void logoutAllRejectsMissingBearerPrefix() {
        ResponseEntity<?> r = controller.logoutAll("bad-format");
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void logoutAllReturns401WhenNoUsername() {
        when(jwtUtils.extractUsernameFromToken("jwt")).thenReturn(null);
        ResponseEntity<?> r = controller.logoutAll("Bearer jwt");
        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void logoutAllSucceeds() {
        when(jwtUtils.extractUsernameFromToken("jwt")).thenReturn("alice");
        when(jwtUtils.extractUserIdFromToken("jwt")).thenReturn("42");
        doNothing().when(jwtUtils).invalidateAllUserTokens("42");

        ResponseEntity<?> r = controller.logoutAll("Bearer jwt");
        assertEquals(200, r.getStatusCode().value());
        assertBodyCode(r, 200);
    }

    @Test
    void logoutAllHandlesException() {
        when(jwtUtils.extractUsernameFromToken("jwt")).thenReturn("alice");
        when(jwtUtils.extractUserIdFromToken("jwt")).thenReturn("42");
        doThrow(new RuntimeException("fail")).when(jwtUtils).invalidateAllUserTokens("42");

        ResponseEntity<?> r = controller.logoutAll("Bearer jwt");
        assertEquals(500, r.getStatusCode().value());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static User buildUser(Long id, String username, User.Role role,
                                  String orgTags, String primaryOrg) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setOrgTags(orgTags);
        u.setPrimaryOrg(primaryOrg);
        return u;
    }

    @SuppressWarnings("unchecked")
    private static void assertBodyCode(ResponseEntity<?> response, int expected) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(expected, body.get("code"));
    }
}
