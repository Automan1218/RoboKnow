package com.yizhaoqi.smartpai.agent.tool;

import com.yizhaoqi.smartpai.agent.AgentContext;
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
        logger.info("注册 Agent 工具: {}", tool.name());
    }

    public String execute(String toolName, String input, AgentContext context) {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            return "错误：未知工具 '" + toolName + "'，可用工具: " + String.join(", ", tools.keySet());
        }
        try {
            logger.info("执行工具: {}，输入: {}", toolName, input);
            return tool.execute(input, context);
        } catch (Exception e) {
            logger.error("工具 {} 执行失败: {}", toolName, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
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
