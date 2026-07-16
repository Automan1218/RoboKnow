package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, String> {

    List<ConversationSession> findByUserIdAndStatusOrderByLastActiveAtDesc(
            String userId, ConversationSession.Status status);

    Optional<ConversationSession> findTopByUserIdAndStatusOrderByLastActiveAtDesc(
            String userId, ConversationSession.Status status);

    List<ConversationSession> findByStatusOrderByLastActiveAtDesc(ConversationSession.Status status);

    @Query("SELECT s FROM ConversationSession s WHERE s.status = 'ACTIVE' " +
           "AND s.lastActiveAt < :cutoff")
    List<ConversationSession> findIdleSessions(@Param("cutoff") LocalDateTime cutoff);
}
