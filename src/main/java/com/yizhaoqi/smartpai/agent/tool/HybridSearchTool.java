package com.yizhaoqi.smartpai.agent.tool;

import com.yizhaoqi.smartpai.agent.AgentContext;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.springframework.stereotype.Component;

import java.util.List;

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
        return "在知识库中搜索相关文档（KNN 向量搜索 + BM25 文本匹配）。输入：搜索查询字符串";
    }

    @Override
    public String execute(String input, AgentContext context) {
        List<SearchResult> results = hybridSearchService.searchWithPermission(input.trim(), context.getUserId(), 5);
        if (results.isEmpty()) {
            return "未找到与 \"" + input.trim() + "\" 相关的文档";
        }
        StringBuilder sb = new StringBuilder("找到 ").append(results.size()).append(" 条相关文档:\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String snippet = r.getTextContent();
            if (snippet.length() > 400) {
                snippet = snippet.substring(0, 400) + "…";
            }
            String file = r.getFileName() != null ? r.getFileName() : r.getFileMd5();
            sb.append(String.format("[%d] (%s) %s\n", i + 1, file, snippet));
        }
        return sb.toString();
    }
}
