package com.yizhaoqi.smartpai.agent.tool;

import com.yizhaoqi.smartpai.agent.AgentContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    @Test
    void registerExecuteAndDescribeTool() {
        ToolRegistry registry = new ToolRegistry();
        AgentTool echo = new StubTool("echo", "Echo input", false);

        registry.register(echo);

        assertTrue(registry.hasTool("echo"));
        assertEquals("echo:hello", registry.execute("echo", "hello", null));
        assertTrue(registry.getToolDescriptions().contains("echo - Echo input"));
    }

    @Test
    void executeReturnsMessageForUnknownTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("known", "Known tool", false));

        String result = registry.execute("missing", "input", null);

        assertTrue(result.contains("missing"));
        assertTrue(result.contains("known"));
    }

    @Test
    void executeConvertsToolExceptionToErrorMessage() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new StubTool("broken", "Broken tool", true));

        String result = registry.execute("broken", "input", null);

        assertTrue(result.contains("boom"));
    }

    private record StubTool(String name, String description, boolean fail) implements AgentTool {

        @Override
        public String execute(String input, AgentContext context) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return name + ":" + input;
        }
    }
}
