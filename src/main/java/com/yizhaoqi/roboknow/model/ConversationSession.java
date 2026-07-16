package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_sessions", indexes = {
        @Index(name = "idx_cs_user_id", columnList = "user_id"),
        @Index(name = "idx_cs_last_active", columnList = "last_active_at")
})
public class ConversationSession {

    @Id
    @Column(length = 36)
    private String id; // UUID, doubles as Redis convId

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId; // username (matches Redis key convention)

    @Column(length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @Column(name = "round_count")
    private int roundCount = 0; // tracks rounds for incremental extraction

    @Column(name = "next_turn_seq", nullable = false)
    private int nextTurnSeq = 0; // atomically allocated per-session turn counter

    public enum Status {
        ACTIVE, ARCHIVED
    }
}
