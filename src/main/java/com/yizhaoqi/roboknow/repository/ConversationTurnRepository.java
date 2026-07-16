package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationTurnRepository extends JpaRepository<ConversationTurn, Long> {

    Optional<ConversationTurn> findByConvIdAndRequestId(String convId, String requestId);

    Optional<ConversationTurn> findFirstByConvIdAndStatusOrderByTurnSeqAsc(
            String convId, ConversationTurn.Status status);

    boolean existsByConvIdAndStatusIn(String convId, List<ConversationTurn.Status> statuses);

    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'PROCESSING', t.attemptToken = :attemptToken, " +
           "t.startedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.status = 'PENDING'")
    int claimForProcessing(@Param("id") Long id, @Param("attemptToken") String attemptToken);

    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'COMPLETE', t.assistantContent = :assistantContent, " +
           "t.completedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.attemptToken = :attemptToken " +
           "AND t.status = 'PROCESSING'")
    int completeIfOwned(@Param("id") Long id, @Param("attemptToken") String attemptToken,
                         @Param("assistantContent") String assistantContent);

    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'FAILED', t.errorCode = :errorCode, " +
           "t.completedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.attemptToken = :attemptToken " +
           "AND t.status = 'PROCESSING'")
    int failIfOwned(@Param("id") Long id, @Param("attemptToken") String attemptToken,
                     @Param("errorCode") String errorCode);
}
