package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.service.AiTokenUsageService;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiUsageControllerTest {

    @Test
    void getUsageReturnsCurrentUserUsage() {
        AiTokenUsageService usageService = mock(AiTokenUsageService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        AiUsageController controller = new AiUsageController(usageService, jwtUtils);
        LocalDateTime start = LocalDateTime.parse("2026-06-01T00:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-06-05T23:59:59");
        when(jwtUtils.extractUsernameFromToken("token")).thenReturn("admin");
        when(usageService.getUserUsage("admin", start, end)).thenReturn(Map.of(
            "summary", Map.of("totalTokens", 42L),
            "records", List.of()
        ));

        ResponseEntity<?> response = controller.getUsage("Bearer token", null, "2026-06-01", "2026-06-05");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(200, body.get("code"));
        assertEquals("Token usage fetched successfully", body.get("message"));
    }
}
