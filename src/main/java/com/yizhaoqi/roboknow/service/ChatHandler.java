package com.yizhaoqi.roboknow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final AgentStopService agentStopService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHandler(AgentStopService agentStopService) {
        this.agentStopService = agentStopService;
    }

    public void stopResponse(String userId, WebSocketSession session) {
        String sessionId = session.getId();
        logger.info("收到停止请求，用户: {}，会话: {}", userId, sessionId);
        agentStopService.requestStop(sessionId);
        try {
            Map<String, Object> response = Map.of(
                "type", "stop",
                "message", "响应已停止",
                "timestamp", System.currentTimeMillis(),
                "date", java.time.Instant.now().toString()
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception e) {
            logger.error("发送停止确认失败: {}", e.getMessage(), e);
        }
    }
}
