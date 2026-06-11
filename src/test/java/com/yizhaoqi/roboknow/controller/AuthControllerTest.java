package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private AuthController controller;
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        controller = new AuthController();
        jwtUtils = mock(JwtUtils.class);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
    }

    @Test
    void refreshTokenRejectsNullToken() {
        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest(null));
        assertEquals(400, response.getStatusCode().value());
        assertBodyCode(response, 400);
    }

    @Test
    void refreshTokenRejectsEmptyToken() {
        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest(""));
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void refreshTokenRejectsInvalidRefreshToken() {
        when(jwtUtils.validateRefreshToken("bad-token")).thenReturn(false);
        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest("bad-token"));
        assertEquals(401, response.getStatusCode().value());
        assertBodyCode(response, 401);
    }

    @Test
    void refreshTokenReturns401WhenUsernameCannotBeExtracted() {
        when(jwtUtils.validateRefreshToken("tok")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn(null);
        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest("tok"));
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refreshTokenReturns401WhenUsernameIsEmpty() {
        when(jwtUtils.validateRefreshToken("tok")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("");
        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest("tok"));
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refreshTokenSucceedsAndReturnsNewTokens() {
        when(jwtUtils.validateRefreshToken("valid")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("valid")).thenReturn("alice");
        when(jwtUtils.generateToken("alice")).thenReturn("new-tok");
        when(jwtUtils.generateRefreshToken("alice")).thenReturn("new-refresh");

        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest("valid"));
        assertEquals(200, response.getStatusCode().value());
        assertBodyCode(response, 200);
    }

    @Test
    void refreshTokenHandlesCustomException() {
        when(jwtUtils.validateRefreshToken("tok")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        when(jwtUtils.generateToken("alice"))
                .thenThrow(new CustomException("User not found", HttpStatus.NOT_FOUND));

        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest("tok"));
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void refreshTokenHandlesUnexpectedException() {
        when(jwtUtils.validateRefreshToken("tok")).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken("tok")).thenReturn("alice");
        when(jwtUtils.generateToken("alice")).thenThrow(new RuntimeException("DB error"));

        ResponseEntity<?> response = controller.refreshToken(new RefreshTokenRequest("tok"));
        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void customBackendErrorReturnsRequestedStatusAndMessage() {
        ResponseEntity<?> response = controller.customBackendError("403", "forbidden");
        assertEquals(403, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("forbidden", body.get("message"));
        assertEquals(403, body.get("code"));
    }

    @Test
    void customBackendErrorReturns500ForInternalError() {
        ResponseEntity<?> response = controller.customBackendError("500", "oops");
        assertEquals(500, response.getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    private static void assertBodyCode(ResponseEntity<?> response, int expectedCode) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(expectedCode, body.get("code"));
    }
}
