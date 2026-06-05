package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.AiTokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiTokenUsageRepository extends JpaRepository<AiTokenUsage, Long> {

    List<AiTokenUsage> findByUsernameAndCreatedAtBetweenOrderByCreatedAtDesc(
        String username,
        LocalDateTime start,
        LocalDateTime end
    );
}
