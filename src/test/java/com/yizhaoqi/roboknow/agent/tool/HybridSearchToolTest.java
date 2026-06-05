package com.yizhaoqi.roboknow.agent.tool;

import com.yizhaoqi.roboknow.agent.AgentContext;
import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.service.HybridSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridSearchToolTest {

    @Test
    void executeReturnsEnglishEvidenceChunks() {
        HybridSearchService searchService = mock(HybridSearchService.class);
        ToolRegistry registry = new ToolRegistry();
        HybridSearchTool tool = new HybridSearchTool(searchService, registry);
        SearchResult result = new SearchResult("md5", 7, "Henry Hou studied Software Engineering at NUS.", 0.92d);
        result.setFileName("henry-hou-cv.pdf");
        when(searchService.searchWithPermission("What did Henry study?", "alice", 10)).thenReturn(List.of(result));

        String output = tool.execute("What did Henry study?", context());

        assertTrue(output.contains("Found 1 relevant document chunk"));
        assertTrue(output.contains("Source #1"));
        assertTrue(output.contains("henry-hou-cv.pdf"));
        assertTrue(output.contains("Henry Hou studied Software Engineering at NUS."));
        assertFalse(output.contains("找到"));
    }

    @Test
    void executeReturnsEnglishNoEvidenceMessage() {
        HybridSearchService searchService = mock(HybridSearchService.class);
        ToolRegistry registry = new ToolRegistry();
        HybridSearchTool tool = new HybridSearchTool(searchService, registry);
        when(searchService.searchWithPermission("unknown fact", "alice", 10)).thenReturn(List.of());

        String output = tool.execute("unknown fact", context());

        assertTrue(output.contains("No relevant document chunks found"));
        assertFalse(output.contains("未找到"));
    }

    private AgentContext context() {
        return new AgentContext("alice", "question", "conversation-1", List.of(), null);
    }
}
