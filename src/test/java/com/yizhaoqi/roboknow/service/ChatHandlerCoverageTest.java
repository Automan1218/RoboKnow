package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHandlerCoverageTest {

    @Test
    void stopResponseRequestsStopAndSendsJsonConfirmation() throws Exception {
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-1");

        ChatHandler handler = new ChatHandler(stopService);
        handler.stopResponse("alice", session);

        verify(stopService).requestStop("sess-1");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertTrue(payload.contains("\"type\":\"stop\""));
        assertTrue(payload.contains("\"message\""));
    }

    @Test
    void stopResponseSwallowsSendFailureGracefully() throws Exception {
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-1");
        doThrow(new IllegalStateException("WebSocket closed"))
                .when(session).sendMessage(any(TextMessage.class));

        ChatHandler handler = new ChatHandler(stopService);

        assertDoesNotThrow(() -> handler.stopResponse("alice", session));
        // stop flag must still be set even when send fails
        verify(stopService).requestStop("sess-1");
    }
}
