package com.yizhaoqi.roboknow.controller;

import com.yizhaoqi.roboknow.handler.ChatWebSocketHandler;
import com.yizhaoqi.roboknow.utils.LogUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 曾经 extends TextWebSocketHandler 并重写 handleTextMessage，但从未被注册为实际的
 * WebSocket handler（真正接 /chat/{token} 的是 WebSocketConfig 里的 chatWebSocketHandler），
 * 那段代码永远不会被调用——2026-07-16 随 ChatHandler.processMessage 一起删除。
 * 这个类现在只承担 /websocket-token 这一个真实的 REST 端点。
 */
@Component
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    /**
     * 获取WebSocket停止指令Token
     */
    @GetMapping("/websocket-token")
    public ResponseEntity<?> getWebSocketToken() {
        try {
            String cmdToken = ChatWebSocketHandler.getInternalCmdToken();
            
            // 检查token是否有效
            if (cmdToken == null || cmdToken.trim().isEmpty()) {
                return ResponseEntity.status(500).body(Map.of(
                    "code", 500,
                    "message", "Token生成失败",
                    "data", null
                ));
            }
            
            return ResponseEntity.ok(Map.of(
                "code", 200,
                "message", "获取WebSocket停止指令Token成功",
                "data", Map.of("cmdToken", cmdToken)
            ));
            
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_WEBSOCKET_TOKEN", "system", "获取WebSocket Token失败", e);
            return ResponseEntity.status(500).body(Map.of(
                "code", 500,
                "message", "服务器内部错误：" + e.getMessage(),
                "data", null
            ));
        }
    }
}
