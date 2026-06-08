package com.yizhaoqi.roboknow.entity;

import lombok.Data;

@Data
public class SearchResult {
    private String fileMd5;
    private Integer chunkId;
    private String textContent;
    private Double score;
    private String fileName;
    private String userId;
    private String orgTag;
    private Boolean isPublic;
    /** vectorId of the parent DocumentVector. Null for old indexed docs. */
    private Long parentChunkId;
    /** Parent chunk text fetched from MySQL. When non-null, used as LLM context instead of textContent. */
    private String contextText;

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, score, null, null, false, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, score, null, null, false, fileName);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
    }
}
