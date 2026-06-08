package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
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

    /** Null for parent chunks. For child chunks: the vectorId of the owning parent. */
    @Column(name = "parent_chunk_id")
    private Long parentChunkId;

    /** True = parent chunk (not vectorized). False = child chunk (vectorized, indexed in ES). */
    @Column(name = "is_parent", nullable = false)
    private boolean isParent = false;
}
