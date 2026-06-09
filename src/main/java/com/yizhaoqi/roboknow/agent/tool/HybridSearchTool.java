package com.yizhaoqi.roboknow.agent.tool;

import com.yizhaoqi.roboknow.agent.AgentContext;
import com.yizhaoqi.roboknow.entity.SearchResult;
import com.yizhaoqi.roboknow.service.HybridSearchService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // 用小子块做精准向量召回，再 small-to-big 回溯到父块，喂给 LLM 完整上下文。
        List<SearchResult> results = hybridSearchService.searchWithPermission(query, context.getUserId(), 10);
        if (results.isEmpty()) {
            return "No relevant document chunks found for query: " + query;
        }

        List<ParentBlock> blocks = expandToParents(results);

        StringBuilder sb = new StringBuilder("Found ")
            .append(blocks.size())
            .append(blocks.size() == 1 ? " relevant document chunk:\n" : " relevant document chunks:\n");

        for (int i = 0; i < blocks.size(); i++) {
            ParentBlock block = blocks.get(i);
            String snippet = truncate(block.content, 2000);
            sb.append(String.format("[Source #%d] File: %s, Chunk: %s, Score: %.4f\n%s\n",
                i + 1,
                block.file,
                block.chunkLabel,
                block.score,
                snippet));
        }
        return sb.toString();
    }

    /**
     * Small-to-big：将命中的子块按 (fileMd5, parentChunkId) 去重，回溯到父块全文。
     * 同一父块只输出一次（取最高子块分数），避免把同一段落的多个子块重复喂给 LLM，
     * 也保证父块内的完整信息（例如简历中同一"实习经历"段落的多条要点）一次性呈现。
     * 父块信息缺失（旧数据）时回退到子块本身。命中顺序即分数序，用 LinkedHashMap 保序。
     */
    private List<ParentBlock> expandToParents(List<SearchResult> results) {
        Map<String, ParentBlock> byParent = new LinkedHashMap<>();
        for (SearchResult r : results) {
            boolean hasParent = r.getParentChunkId() != null
                && r.getParentContent() != null && !r.getParentContent().isBlank();

            String key = hasParent
                ? r.getFileMd5() + "#p" + r.getParentChunkId()
                : r.getFileMd5() + "#c" + r.getChunkId();

            double score = r.getScore() == null ? 0.0 : r.getScore();
            ParentBlock existing = byParent.get(key);
            if (existing == null) {
                String file = r.getFileName() != null ? r.getFileName() : r.getFileMd5();
                String content = hasParent ? r.getParentContent() : r.getTextContent();
                String label = hasParent ? ("parent-" + r.getParentChunkId()) : String.valueOf(r.getChunkId());
                byParent.put(key, new ParentBlock(file, label, content, score));
            } else if (score > existing.score) {
                existing.score = score; // 保留父块下最高子块分数
            }
        }
        return new ArrayList<>(byParent.values());
    }

    /** 去重后的父块（喂给 LLM 的一个 Source）。 */
    private static final class ParentBlock {
        final String file;
        final String chunkLabel;
        final String content;
        double score;

        ParentBlock(String file, String chunkLabel, String content, double score) {
            this.file = file;
            this.chunkLabel = chunkLabel;
            this.content = content;
            this.score = score;
        }
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
