package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.agent.ReactAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHandlerCoverageTest {

    @Test
    void processMessageDelegatesToReactAgentAsynchronously() {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);
        handler.processMessage("alice", "conv-1", "hello world", session);

        // CompletableFuture.runAsync — verify within 2 s
        verify(reactAgentService, timeout(2000))
                .processMessage("alice", "conv-1", "hello world", session);
    }

    @Test
    void stopResponseRequestsStopAndSendsJsonConfirmation() throws Exception {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-1");

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);
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
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-1");
        doThrow(new IllegalStateException("WebSocket closed"))
                .when(session).sendMessage(any(TextMessage.class));

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);

        assertDoesNotThrow(() -> handler.stopResponse("alice", session));
        // stop flag must still be set even when send fails
        verify(stopService).requestStop("sess-1");
    }

    @Test
    void processMessageSwallowsReactAgentExceptionViaCompletableFuture() throws Exception {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-err");
        doThrow(new RuntimeException("agent crash"))
                .when(reactAgentService).processMessage(any(), any(), any(), any());

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);

        // should not throw from the caller's thread
        assertDoesNotThrow(() -> handler.processMessage("alice", "conv-1", "hi", session));
    }
}
