package com.yizhaoqi.roboknow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.agent.ReactAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);

    private final ReactAgentService reactAgentService;
    private final AgentStopService agentStopService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHandler(ReactAgentService reactAgentService, AgentStopService agentStopService) {
        this.reactAgentService = reactAgentService;
        this.agentStopService = agentStopService;
    }

    public void processMessage(String userId, String convId, String userMessage, WebSocketSession session) {
        logger.info("ChatHandler 接收消息，用户: {}，convId: {}，会话: {}", userId, convId, session.getId());
        CompletableFuture.runAsync(() ->
            reactAgentService.processMessage(userId, convId, userMessage, session)
        ).exceptionally(ex -> {
            logger.error("ReactAgent 异步任务异常: {}", ex.getMessage(), ex);
            sendError(session, "处理消息时发生内部错误");
            return null;
        });
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

    private void sendError(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("error", message))));
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
}
