package com.yizhaoqi.roboknow.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatControllerTest {

    @Test
    void getWebSocketTokenReturnsNonEmptyToken() {
        ChatController controller = new ChatController();

        ResponseEntity<?> response = controller.getWebSocketToken();

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(200, body.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertNotNull(data.get("cmdToken"));
        assertFalse(((String) data.get("cmdToken")).isBlank());
    }

    @Test
    void getWebSocketTokenIsDeterministicWithinProcess() {
        ChatController c1 = new ChatController();
        ChatController c2 = new ChatController();

        ResponseEntity<?> r1 = c1.getWebSocketToken();
        ResponseEntity<?> r2 = c2.getWebSocketToken();

        @SuppressWarnings("unchecked")
        String t1 = (String) ((Map<String, Object>) ((Map<String, Object>) r1.getBody()).get("data")).get("cmdToken");
        @SuppressWarnings("unchecked")
        String t2 = (String) ((Map<String, Object>) ((Map<String, Object>) r2.getBody()).get("data")).get("cmdToken");

        assertEquals(t1, t2, "INTERNAL_CMD_TOKEN should be same constant across instances");
    }
}
