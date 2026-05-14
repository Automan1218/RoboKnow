package com.yizhaoqi.smartpai.agent.tool;

import com.yizhaoqi.smartpai.agent.AgentContext;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SummarizationTool implements AgentTool {

    private final DeepSeekClient deepSeekClient;

    public SummarizationTool(DeepSeekClient deepSeekClient, ToolRegistry toolRegistry) {
        this.deepSeekClient = deepSeekClient;
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return "summarize";
    }

    @Override
    public String description() {
        return "对长文本段落进行摘要，压缩上下文窗口。输入：需要摘要的文本内容";
    }

    @Override
    public String execute(String input, AgentContext context) {
        if (input == null || input.isBlank()) {
            return "输入文本为空，无法生成摘要";
        }
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content",
                   "你是文档摘要助手。请对以下文本提取关键信息并做简洁摘要，控制在300字以内，保留重要数据、结论和关键点。"),
            Map.of("role", "user", "content", input)
        );
        String summary = deepSeekClient.chatBlocking(messages);
        return summary.isBlank() ? "摘要生成失败，请重试" : "摘要：" + summary;
    }
}
