package com.yizhaoqi.roboknow.agent.tool;

import com.yizhaoqi.roboknow.agent.AgentContext;
import com.yizhaoqi.roboknow.client.AiUsageMetadata;
import com.yizhaoqi.roboknow.client.OpenAiClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SummarizationTool implements AgentTool {

    private final OpenAiClient openAiClient;

    public SummarizationTool(OpenAiClient openAiClient, ToolRegistry toolRegistry) {
        this.openAiClient = openAiClient;
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return "summarize";
    }

    @Override
    public String description() {
        return "Summarize long text to compress context. Input: the text that needs a concise summary.";
    }

    @Override
    public String execute(String input, AgentContext context) {
        if (input == null || input.isBlank()) {
            return "Input text is empty, so no summary can be generated.";
        }
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content",
                "You are a document summarization assistant. Extract key information from the text below and produce a concise summary under 300 words. Preserve important data, conclusions, and key points."),
            Map.of("role", "user", "content", input)
        );
        String summary = openAiClient.chatBlocking(
            messages,
            new AiUsageMetadata(context.getUserId(), context.getConversationId(), "summarize_tool")
        );
        return summary == null || summary.isBlank() ? "Summary generation failed. Please try again." : "Summary: " + summary;
    }
}
