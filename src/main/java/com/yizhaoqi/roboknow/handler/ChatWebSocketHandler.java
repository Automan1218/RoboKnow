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
    private static final String ATTR_USERNAME = "ws_username";
    private static final String ATTR_TOKEN = "ws_token";

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
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractToken(session);

        // 必须走完整校验（签名 + 过期 + Redis 黑名单/缓存）：登出后的旧 token、
        // 被踢下线的 token 在这里直接拒绝，防止用残留 token 以他人身份建立连接
        if (token == null || !jwtUtils.validateToken(token)) {
            logger.warn("WebSocket连接被拒绝：token无效或已失效，sessionId={}, token={}",
                    session.getId(), maskToken(token));
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid or expired token"));
            return;
        }

        String username = jwtUtils.extractUsernameFromToken(token);
        if (username == null) {
            logger.warn("WebSocket连接被拒绝：无法从token中提取用户名，sessionId={}, token={}",
                    session.getId(), maskToken(token));
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid token"));
            return;
        }

        session.getAttributes().put(ATTR_USERNAME, username);
        session.getAttributes().put(ATTR_TOKEN, token);
        sessions.put(username, session);
        // Migrate old Redis key on first connection for existing users
        sessionManager.migrateOldKeyIfPresent(username);
        logger.info("WebSocket connected userId={} sessionId={}", username, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userId = extractUserId(session);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Unauthenticated session"));
            return;
        }

        // 长连接期间 token 可能被登出/全设备登出拉黑，每条消息复查一次（仅 Redis 查询，开销可忽略）
        String token = (String) session.getAttributes().get(ATTR_TOKEN);
        if (token == null || !jwtUtils.validateToken(token)) {
            logger.warn("WebSocket消息被拒绝：token已失效，用户ID: {}，会话ID: {}", userId, session.getId());
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Token expired or revoked"));
            return;
        }

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
        if (userId != null) {
            sessions.remove(userId);
        }
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

    /** 身份只在握手时验证一次并存入 session attributes，未通过握手验证的连接拿不到身份 */
    private String extractUserId(WebSocketSession session) {
        Object username = session.getAttributes().get(ATTR_USERNAME);
        return username != null ? username.toString() : null;
    }

    private String extractToken(WebSocketSession session) {
        if (session.getUri() == null) {
            return null;
        }
        String path = session.getUri().getPath();
        String[] segments = path.split("/");
        String token = segments.length > 0 ? segments[segments.length - 1] : null;
        return token != null && !token.isBlank() ? token : null;
    }

    /** 日志脱敏：JWT 是凭证，完整值不能落日志 */
    private String maskToken(String token) {
        if (token == null || token.length() <= 10) {
            return "***";
        }
        return token.substring(0, 10) + "...";
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
