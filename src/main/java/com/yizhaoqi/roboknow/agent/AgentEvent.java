package com.yizhaoqi.roboknow.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentEvent {

    private final Map<String, Object> payload;

    private AgentEvent(Map<String, Object> payload) {
        this.payload = payload;
    }

    public static AgentEvent stateChange(AgentState state, int iteration) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "agent_state");
        map.put("state", state.name());
        map.put("iteration", iteration);
        return new AgentEvent(map);
    }

    public static AgentEvent thought(String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "thought");
        map.put("content", content);
        return new AgentEvent(map);
    }

    public static AgentEvent action(String tool, String input) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "action");
        map.put("tool", tool);
        map.put("input", input);
        return new AgentEvent(map);
    }

    public static AgentEvent observation(String tool, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "observation");
        map.put("tool", tool);
        map.put("content", content);
        return new AgentEvent(map);
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
