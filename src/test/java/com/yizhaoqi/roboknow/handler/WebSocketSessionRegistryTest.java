package com.yizhaoqi.roboknow.handler;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketSessionRegistryTest {

    @Test
    void getReturnsEmptyWhenNeverRegistered() {
        var registry = new WebSocketSessionRegistry();
        assertTrue(registry.get("nobody").isEmpty());
    }

    @Test
    void registerThenGetReturnsSessionWhenOpen() {
        var registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        registry.register("alice", session);
        assertEquals(session, registry.get("alice").orElseThrow());
    }

    @Test
    void getReturnsEmptyWhenSessionClosed() {
        var registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        registry.register("bob", session);
        assertTrue(registry.get("bob").isEmpty());
    }

    @Test
    void unregisterRemovesSession() {
        var registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        registry.register("carol", session);
        registry.unregister("carol");
        assertTrue(registry.get("carol").isEmpty());
    }
}
