package com.yizhaoqi.roboknow.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

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

    @Column(length = 32)
    private String modelVersion;

    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "parent_chunk_id")
    private Long parentChunkId;

    @Column(name = "parent_content", columnDefinition = "LONGTEXT")
    private String parentContent;

    @Column(name = "is_parent", nullable = false)
    private boolean isParent = false;

    public void setParentChunkId(Long parentChunkId) {
        this.parentChunkId = parentChunkId;
    }
}
