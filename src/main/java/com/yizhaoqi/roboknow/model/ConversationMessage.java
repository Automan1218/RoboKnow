package com.yizhaoqi.roboknow.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversation_messages",
        uniqueConstraints = @UniqueConstraint(name = "uk_conv_seq", columnNames = {"conv_id", "seq"}),
        indexes = {
                @Index(name = "idx_conv_seq", columnList = "conv_id,seq")
        })
public class ConversationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conv_id", nullable = false, length = 36)
    private String convId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
