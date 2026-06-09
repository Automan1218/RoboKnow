package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Blob;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    /**
     * 父块编号（父子分块）。同一父块下的多个子块共享同一个 parentChunkId。
     * 召回时用子块（小、精准）匹配，再回溯到父块（大、上下文完整）喂给 LLM。
     * 旧数据为空时检索侧回退到 textContent。
     */
    @Column(name = "parent_chunk_id")
    private Integer parentChunkId;

    /**
     * 父块完整文本（反规范化存储，避免检索时回表 join）。
     * 显式 LONGTEXT：Hibernate 6 对 @Lob String 在 MySQL 上映射不稳定（可能落成 VARCHAR(255)），
     * 父块文本可达数千字符，必须用大文本列。
     */
    @Column(name = "parent_content", columnDefinition = "LONGTEXT")
    private String parentContent;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;
}