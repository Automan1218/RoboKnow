package com.yizhaoqi.roboknow.entity;

import lombok.Getter;
import lombok.Setter;

// 文件分块内容实体类
@Setter
@Getter
public class TextChunk {

    // Getters/Setters
    private int chunkId;            // 子块序号
    private String content;         // 子块内容（向量化的单元）
    private Integer parentChunkId;  // 父块编号（父子分块）
    private String parentContent;   // 父块完整文本

    // 构造方法（向后兼容，无父子分块信息）
    public TextChunk(int chunkId, String content) {
        this(chunkId, content, null, null);
    }

    // 构造方法（含父子分块信息）
    public TextChunk(int chunkId, String content, Integer parentChunkId, String parentContent) {
        this.chunkId = chunkId;
        this.content = content;
        this.parentChunkId = parentChunkId;
        this.parentContent = parentContent;
    }
}
