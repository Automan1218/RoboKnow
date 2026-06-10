package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_memory_facts", indexes = {
        @Index(name = "idx_umf_user_id", columnList = "user_id"),
        @Index(name = "idx_umf_content_hash", columnList = "content_hash"),
        @Index(name = "idx_umf_created_at", columnList = "created_at")
})
public class UserMemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash; // MD5 hex of normalized content for dedup

    @Column(name = "source_conversation_id", length = 36)
    private String sourceConversationId;

    @Column(name = "hit_count")
    private int hitCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
