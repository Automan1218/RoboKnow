package com.yizhaoqi.roboknow.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.service.ChatHandler;
import com.yizhaoqi.roboknow.service.SessionManager;
import com.yizhaoqi.roboknow.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    private final ChatHandler chatHandler;
    private final SessionManager sessionManager;
    private final JwtUtils jwtUtils;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatHandler chatHandler,
                                 SessionManager sessionManager,
                                 JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.sessionManager = sessionManager;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = extractUserId(session);
        sessions.put(userId, session);
        // Migrate old Redis key on first connection for existing users
        sessionManager.migrateOldKeyIfPresent(userId);
        logger.info("WebSocket connected userId={} sessionId={} uri={}",
                userId, session.getId(), session.getUri().getPath());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        try {
            String payload = message.getPayload();
            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}",
                    userId, session.getId(), payload.length());

            if (payload.trim().startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = objectMapper.readValue(payload, Map.class);
                    String type = (String) json.get("type");
                    String internalToken = (String) json.get("_internal_cmd_token");

                    // Stop command (internal only)
                    if ("stop".equals(type) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        logger.info("收到有效的停止按钮指令，用户ID: {}，会话ID: {}", userId, session.getId());
                        chatHandler.stopResponse(userId, session);
                        return;
                    }

                    // Chat message with optional convId
                    String userMessage = (String) json.get("message");
                    if (userMessage != null && !userMessage.isBlank()) {
                        String convId = resolveConvId(userId, (String) json.get("convId"));
                        chatHandler.processMessage(userId, convId, userMessage, session);
                        return;
                    }
                    // Fall through to plain-text path if no "message" key
                } catch (Exception parseError) {
                    logger.debug("JSON parse failed, treating as plain text: {}", parseError.getMessage());
                }
            }

            // Backward compat: plain text message (no convId)
            if (!payload.isBlank()) {
                String convId = sessionManager.getActiveConvId(userId);
                chatHandler.processMessage(userId, convId, payload, session);
            }

        } catch (Exception e) {
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}",
                    userId, session.getId(), e.getMessage(), e);
            sendError(session, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        sessions.remove(userId);
        logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}",
                userId, session.getId(), status);
    }

    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private String resolveConvId(String userId, String requestedConvId) {
        if (requestedConvId != null && !requestedConvId.isBlank()) {
            sessionManager.verifyOwnership(userId, requestedConvId);
            return requestedConvId;
        }
        return sessionManager.getActiveConvId(userId);
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String jwtToken = segments[segments.length - 1];
        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        if (username == null) {
            logger.warn("无法从JWT令牌中提取用户名，使用令牌作为用户ID: {}", jwtToken);
            return jwtToken;
        }
        logger.debug("从JWT令牌中提取的用户名: {}", username);
        return username;
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        try {
            session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(Map.of("error", errorMessage))));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
}
