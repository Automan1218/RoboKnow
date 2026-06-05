package com.yizhaoqi.roboknow.agent.tool;

import com.yizhaoqi.roboknow.agent.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
        logger.info("Registered Agent tool: {}", tool.name());
    }

    public String execute(String toolName, String input, AgentContext context) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            return "Error: unknown tool '" + toolName + "'. Available tools: " + String.join(", ", tools.keySet());
        }
        try {
            logger.info("Executing tool: {}, input: {}", toolName, input);
            return tool.execute(input, context);
        } catch (Exception e) {
            logger.error("Tool {} execution failed: {}", toolName, e.getMessage(), e);
            return "Tool execution failed: " + e.getMessage();
        }
    }

    public String getToolDescriptions() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (AgentTool tool : tools.values()) {
            sb.append(i++).append(". ").append(tool.name())
                .append(" - ").append(tool.description()).append("\n");
        }
        return sb.toString();
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
