package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.service.AiTokenUsageService;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/usage")
public class AiUsageController {

    private final AiTokenUsageService aiTokenUsageService;
    private final JwtUtils jwtUtils;

    public AiUsageController(AiTokenUsageService aiTokenUsageService, JwtUtils jwtUtils) {
        this.aiTokenUsageService = aiTokenUsageService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> getUsage(
        @RequestHeader("Authorization") String authorization,
        @RequestParam(required = false) String username,
        @RequestParam(required = false) String start_date,
        @RequestParam(required = false) String end_date
    ) {
        try {
            String token = authorization.replace("Bearer ", "");
            String currentUsername = jwtUtils.extractUsernameFromToken(token);
            String role = jwtUtils.extractRoleFromToken(token);
            if (currentUsername == null || currentUsername.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "Invalid token"));
            }

            String targetUsername = currentUsername;
            if (username != null && !username.isBlank()) {
                if (!"ADMIN".equals(role)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("code", 403, "message", "Only admins can query another user's token usage"));
                }
                targetUsername = username;
            }

            Map<String, Object> usage = aiTokenUsageService.getUserUsage(
                targetUsername,
                parseDateTime(start_date, false),
                parseDateTime(end_date, true)
            );

            return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "Token usage fetched successfully",
                "data", usage
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", 500, "message", "Failed to fetch token usage: " + e.getMessage()));
        }
    }

    private LocalDateTime parseDateTime(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() == 10) {
            return LocalDateTime.parse(value + (endOfDay ? "T23:59:59" : "T00:00:00"));
        }
        if (value.length() == 16) {
            return LocalDateTime.parse(value + ":00");
        }
        return LocalDateTime.parse(value);
    }
}
