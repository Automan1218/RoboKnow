package com.yizhaoqi.roboknow.entity;

import lombok.Data;

@Data
public class EsDocument {

    private String id;             // 文档唯一标识
    private String fileMd5;        // 文件指纹
    private Integer chunkId;       // 文本分块序号（子块）
    private String textContent;    // 文本内容（子块，用于向量化与召回匹配）
    private Integer parentChunkId; // 父块编号（父子分块）
    private String parentContent;  // 父块完整文本（召回后回溯，喂给 LLM 的上下文）
    private float[] vector;        // 向量数据（768维）
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    private boolean isPublic;      // 是否公开

    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段（向后兼容，无父子分块信息）
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic) {
        this(id, fileMd5, chunkId, content, null, null, vector, modelVersion, userId, orgTag, isPublic);
    }

    /**
     * 完整构造函数，包含父子分块信息
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer parentChunkId, String parentContent,
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
        this.parentChunkId = parentChunkId;
        this.parentContent = parentContent;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.parentChunkId = parentChunkId;
    }


}
