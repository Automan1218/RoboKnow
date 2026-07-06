package com.yizhaoqi.roboknow.agent.tool;

import com.yizhaoqi.roboknow.agent.AgentContext;
import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.service.HybridSearchService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HybridSearchTool implements AgentTool {

    private final HybridSearchService hybridSearchService;

    public HybridSearchTool(HybridSearchService hybridSearchService, ToolRegistry toolRegistry) {
        this.hybridSearchService = hybridSearchService;
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return "hybrid_search";
    }

    @Override
    public String description() {
        return "Search relevant knowledge-base document chunks using hybrid retrieval. Input: a plain-language search query.";
    }

    @Override
    public String execute(String input, AgentContext context) {
        String query = input == null ? "" : input.trim();
        List<SearchResult> results = hybridSearchService.searchWithPermission(query, context.getUserId(), 10);
        if (results.isEmpty()) {
            return "No relevant document chunks found for query: " + query;
        }

        StringBuilder sb = new StringBuilder("Found ")
                .append(results.size())
                .append(results.size() == 1 ? " relevant document chunk:\n" : " relevant document chunks:\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            String contextText = firstNonBlank(result.getContextText(), result.getParentContent(), result.getTextContent());
            int limit = result.getContextText() != null || result.getParentContent() != null ? 4000 : 1200;
            String file = result.getFileName() != null ? result.getFileName() : result.getFileMd5();
            sb.append(String.format("[Source #%d] File: %s, Chunk: %s, Score: %.4f\n%s\n",
                    i + 1,
                    file,
                    result.getChunkId(),
                    result.getScore() == null ? 0.0 : result.getScore(),
                    truncate(contextText, limit)));
        }
        return sb.toString();
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
