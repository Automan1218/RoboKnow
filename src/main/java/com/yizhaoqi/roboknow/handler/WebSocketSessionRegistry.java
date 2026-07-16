package com.yizhaoqi.roboknow.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionRegistry {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public void register(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    public void unregister(String userId) {
        sessions.remove(userId);
    }

    public Optional<WebSocketSession> get(String userId) {
        WebSocketSession session = sessions.get(userId);
        return (session != null && session.isOpen()) ? Optional.of(session) : Optional.empty();
    }
}
