package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.service.ChatHandler;
import com.yizhaoqi.roboknow.service.SessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatControllerTest {

    private ChatController buildController() {
        return new ChatController(mock(ChatHandler.class), mock(SessionManager.class));
    }

    @Test
    void getWebSocketTokenReturnsNonEmptyToken() {
        ChatController controller = buildController();

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
        ChatController c1 = buildController();
        ChatController c2 = buildController();

        ResponseEntity<?> r1 = c1.getWebSocketToken();
        ResponseEntity<?> r2 = c2.getWebSocketToken();

        @SuppressWarnings("unchecked")
        String t1 = (String) ((Map<String, Object>) ((Map<String, Object>) r1.getBody()).get("data")).get("cmdToken");
        @SuppressWarnings("unchecked")
        String t2 = (String) ((Map<String, Object>) ((Map<String, Object>) r2.getBody()).get("data")).get("cmdToken");

        assertEquals(t1, t2, "INTERNAL_CMD_TOKEN should be same constant across instances");
    }

    @Test
    void handleTextMessageDelegatesToChatHandler() throws Exception {
        ChatHandler chatHandler = mock(ChatHandler.class);
        SessionManager sessionManager = mock(SessionManager.class);
        ChatController controller = new ChatController(chatHandler, sessionManager);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-abc");
        when(sessionManager.getActiveConvId("session-abc")).thenReturn("conv-1");

        controller.handleTextMessage(session, new TextMessage("hello"));

        verify(chatHandler).processMessage("session-abc", "conv-1", "hello", session);
    }

    @Test
    void handleTextMessagePropagatesException() {
        ChatHandler chatHandler = mock(ChatHandler.class);
        SessionManager sessionManager = mock(SessionManager.class);
        ChatController controller = new ChatController(chatHandler, sessionManager);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess");
        when(sessionManager.getActiveConvId("sess")).thenReturn("conv-1");
        doThrow(new RuntimeException("agent error"))
                .when(chatHandler).processMessage(any(), any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> controller.handleTextMessage(session, new TextMessage("hi")));
    }
}
