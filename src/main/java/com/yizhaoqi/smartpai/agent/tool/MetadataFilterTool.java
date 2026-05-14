package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.AgentContext;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MetadataFilterTool implements AgentTool {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFilterTool.class);

    private final HybridSearchService hybridSearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetadataFilterTool(HybridSearchService hybridSearchService, ToolRegistry toolRegistry) {
        this.hybridSearchService = hybridSearchService;
        toolRegistry.register(this);
    }

    @Override
    public String name() {
        return "metadata_filter";
    }

    @Override
    public String description() {
        return "按元数据（文件名、部门标签）过滤后搜索文档。"
             + "输入：JSON 格式，例如 {\"query\":\"报告内容\",\"filename\":\"年报\",\"orgTag\":\"工程部\"}";
    }

    @Override
    public String execute(String input, AgentContext context) {
        String query = input;
        String orgTagFilter = null;
        String filenameFilter = null;

        if (input != null && input.trim().startsWith("{")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> params = objectMapper.readValue(input.trim(), Map.class);
                query = params.getOrDefault("query", input);
                orgTagFilter = params.get("orgTag");
                filenameFilter = params.get("filename");
            } catch (Exception e) {
                logger.debug("metadata_filter 输入不是合法 JSON，作为纯文本查询处理");
            }
        }

        // Fetch more candidates for post-filtering
        List<SearchResult> results = hybridSearchService.searchWithPermission(query, context.getUserId(), 20);

        final String finalOrgTag = orgTagFilter;
        final String finalFilename = filenameFilter;

        List<SearchResult> filtered = results.stream()
            .filter(r -> finalOrgTag == null
                      || finalOrgTag.equalsIgnoreCase(r.getOrgTag()))
            .filter(r -> finalFilename == null
                      || (r.getFileName() != null
                          && r.getFileName().toLowerCase().contains(finalFilename.toLowerCase())))
            .limit(5)
            .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            StringBuilder msg = new StringBuilder("按条件未找到相关文档");
            if (orgTagFilter != null) msg.append("（部门/标签：").append(orgTagFilter).append("）");
            if (filenameFilter != null) msg.append("（文件名含：").append(filenameFilter).append("）");
            return msg.toString();
        }

        StringBuilder sb = new StringBuilder("按条件筛选找到 ").append(filtered.size()).append(" 条文档:\n");
        for (int i = 0; i < filtered.size(); i++) {
            SearchResult r = filtered.get(i);
            String snippet = r.getTextContent();
            if (snippet.length() > 400) snippet = snippet.substring(0, 400) + "…";
            String file = r.getFileName() != null ? r.getFileName() : r.getFileMd5();
            String tag = r.getOrgTag() != null ? " [" + r.getOrgTag() + "]" : "";
            sb.append(String.format("[%d] (%s%s) %s\n", i + 1, file, tag, snippet));
        }
        return sb.toString();
    }
}
