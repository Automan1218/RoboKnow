package com.yizhaoqi.roboknow.entity;

import lombok.Data;

@Data
public class EsDocument {

    private String id;
    private String fileMd5;
    private Integer chunkId;
    private String textContent;
    private float[] vector;
    private String modelVersion;
    private String userId;
    private String orgTag;
    private boolean isPublic;
    private Long parentChunkId;
    private String parentContent;

    public EsDocument() {
    }

    public EsDocument(String id, String fileMd5, int chunkId, String content,
            float[] vector, String modelVersion,
            String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, vector, modelVersion, userId, orgTag, isPublic, null);
    }

    public EsDocument(String id, String fileMd5, int chunkId, String content,
            float[] vector, String modelVersion,
            String userId, String orgTag, boolean isPublic, Long parentChunkId) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.parentChunkId = parentChunkId;
    }

    public EsDocument(String id, String fileMd5, int chunkId, String content,
            Integer parentChunkId, String parentContent,
            float[] vector, String modelVersion,
            String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, vector, modelVersion, userId, orgTag, isPublic,
                parentChunkId == null ? null : parentChunkId.longValue());
        this.parentContent = parentContent;
    }
}
