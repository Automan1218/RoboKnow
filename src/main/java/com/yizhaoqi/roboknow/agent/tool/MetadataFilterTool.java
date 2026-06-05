package com.yizhaoqi.roboknow.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.roboknow.agent.AgentContext;
import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.service.HybridSearchService;
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
        return "Search document chunks and optionally filter by metadata. "
            + "Input: JSON such as {\"query\":\"report content\",\"filename\":\"annual report\",\"orgTag\":\"engineering\"}.";
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
                logger.debug("metadata_filter input is not valid JSON; treating it as a plain text query");
            }
        }

        List<SearchResult> results = hybridSearchService.searchWithPermission(query, context.getUserId(), 20);

        final String finalOrgTag = orgTagFilter;
        final String finalFilename = filenameFilter;

        List<SearchResult> filtered = results.stream()
            .filter(r -> finalOrgTag == null || finalOrgTag.equalsIgnoreCase(r.getOrgTag()))
            .filter(r -> finalFilename == null
                || (r.getFileName() != null && r.getFileName().toLowerCase().contains(finalFilename.toLowerCase())))
            .limit(5)
            .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            StringBuilder msg = new StringBuilder("No relevant document chunks found after applying metadata filters");
            if (orgTagFilter != null) {
                msg.append(" (orgTag: ").append(orgTagFilter).append(")");
            }
            if (filenameFilter != null) {
                msg.append(" (filename contains: ").append(filenameFilter).append(")");
            }
            return msg.toString();
        }

        StringBuilder sb = new StringBuilder("Found ")
            .append(filtered.size())
            .append(filtered.size() == 1
                ? " relevant document chunk after applying metadata filters:\n"
                : " relevant document chunks after applying metadata filters:\n");

        for (int i = 0; i < filtered.size(); i++) {
            SearchResult result = filtered.get(i);
            String snippet = truncate(result.getTextContent(), 800);
            String file = result.getFileName() != null ? result.getFileName() : result.getFileMd5();
            String tag = result.getOrgTag() != null ? ", OrgTag: " + result.getOrgTag() : "";
            sb.append(String.format("[Source #%d] File: %s%s, Chunk: %s, Score: %.4f\n%s\n",
                i + 1,
                file,
                tag,
                result.getChunkId(),
                result.getScore() == null ? 0.0 : result.getScore(),
                snippet));
        }
        return sb.toString();
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
