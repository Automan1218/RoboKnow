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

    /** 周期安全网用：找出当前有 PENDING turn 的 convId，唤醒它们的 dispatcher，兜住 submit() 的漏 wake 窗口。 */
    @Query("SELECT DISTINCT t.convId FROM ConversationTurn t WHERE t.status = 'PENDING'")
    List<String> findDistinctConvIdsWithPendingTurns();

    /**
     * 启动恢复用：进程崩溃时可能有 turn 卡在 PROCESSING（LLM 调用中途被杀）。全新进程实例
     * 不可能是它的持有者，重置回 PENDING 让 dispatcher 重新处理。回答从未提交（completeIfOwned
     * 会把状态改成 COMPLETE），所以重置不会丢失或重复已提交的回答。
     */
    @Modifying
    @Query("UPDATE ConversationTurn t SET t.status = 'PENDING', t.attemptToken = NULL, " +
           "t.retryCount = t.retryCount + 1 WHERE t.status = 'PROCESSING'")
    int resetOrphanedProcessingTurnsToPending();

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
