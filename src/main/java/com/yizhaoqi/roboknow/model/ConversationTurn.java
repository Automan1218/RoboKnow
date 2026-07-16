package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_turns",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_conv_turn_seq", columnNames = {"conv_id", "turn_seq"}),
                @UniqueConstraint(name = "uk_conv_request_id", columnNames = {"conv_id", "request_id"})
        },
        indexes = {
                @Index(name = "idx_turn_conv_status", columnList = "conv_id,status")
        })
public class ConversationTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conv_id", nullable = false, length = 36)
    private String convId;

    @Column(name = "turn_seq", nullable = false)
    private int turnSeq;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "user_content", nullable = false, columnDefinition = "TEXT")
    private String userContent;

    @Column(name = "assistant_content", columnDefinition = "TEXT")
    private String assistantContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempt_token", length = 36)
    private String attemptToken;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_code", length = 255)
    private String errorCode;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        PENDING, PROCESSING, COMPLETE, FAILED, CANCELLED
    }
}
