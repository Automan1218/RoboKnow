package com.yizhaoqi.smartpai.agent;

import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

public class AgentContext {

    private final String userId;
    private final String userMessage;
    private final String conversationId;
    private final List<Map<String, String>> history;
    private final WebSocketSession session;

    public AgentContext(String userId, String userMessage, String conversationId,
                        List<Map<String, String>> history, WebSocketSession session) {
        this.userId = userId;
        this.userMessage = userMessage;
        this.conversationId = conversationId;
        this.history = history;
        this.session = session;
    }

    public String getUserId() { return userId; }
    public String getUserMessage() { return userMessage; }
    public String getConversationId() { return conversationId; }
    public List<Map<String, String>> getHistory() { return history; }
    public WebSocketSession getSession() { return session; }
}
