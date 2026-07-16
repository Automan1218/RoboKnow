package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** 悲观写锁：同一 convId 的并发 turnSeq 分配靠这个串行化，锁粒度是单个会话行，不同 convId 互不阻塞。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConversationSession s WHERE s.id = :convId")
    Optional<ConversationSession> lockForUpdate(@Param("convId") String convId);
}
