package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.agent.ReactAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHandlerTest {

    @Test
    void processMessageDelegatesToReactAgentAsynchronously() {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);

        handler.processMessage("alice", "hello", session);

        verify(reactAgentService, timeout(1000)).processMessage("alice", "hello", session);
    }

    @Test
    void stopResponseSetsStopFlagAndSendsConfirmation() throws Exception {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);

        handler.stopResponse("alice", session);

        verify(stopService).requestStop("session-1");
        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains("\"type\":\"stop\""));
    }

    @Test
    void stopResponseSwallowsSendFailuresAfterSettingStopFlag() throws Exception {
        ReactAgentService reactAgentService = mock(ReactAgentService.class);
        AgentStopService stopService = mock(AgentStopService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        doThrow(new IllegalStateException("closed")).when(session).sendMessage(any(TextMessage.class));

        ChatHandler handler = new ChatHandler(reactAgentService, stopService);

        assertDoesNotThrow(() -> handler.stopResponse("alice", session));
        verify(stopService).requestStop("session-1");
    }
}
