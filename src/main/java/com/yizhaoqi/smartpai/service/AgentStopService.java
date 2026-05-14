package com.yizhaoqi.smartpai.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨服务的 Agent 停止信号共享存储，供 ChatHandler 和 ReactAgentService 使用。
 */
@Service
public class AgentStopService {

    private final ConcurrentHashMap<String, Boolean> stopFlags = new ConcurrentHashMap<>();

    public void requestStop(String sessionId) {
        stopFlags.put(sessionId, true);
    }

    public boolean shouldStop(String sessionId) {
        return Boolean.TRUE.equals(stopFlags.get(sessionId));
    }

    public void clear(String sessionId) {
        stopFlags.remove(sessionId);
    }
}
