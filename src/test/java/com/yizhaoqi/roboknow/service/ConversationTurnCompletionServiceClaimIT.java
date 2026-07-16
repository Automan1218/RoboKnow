package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无 class-level @Transactional，且 claimNextPending 在从一个裸后台线程调用时也要能正常工作——
 * 这正是 ConversationTurnWiring.drainAllPending 的真实运行方式（跑在 turnWorkerExecutor
 * 的线程上，没有任何测试框架/Web 请求提供的环境事务）。2026-07-16 上线前的真实进程验收
 * 曾在这条路径上炸掉（claimForProcessing 直接从裸线程调用，TransactionRequiredException），
 * 这个测试就是为了不让同样的坑再犯一次。
 */
@SpringBootTest
@ActiveProfiles("dev")
class ConversationTurnCompletionServiceClaimIT {

    @Autowired private ConversationTurnCompletionService completionService;
    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationSessionRepository sessionRepository;

    private String newConvId() {
        String convId = "it-claim-" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);
        return convId;
    }

    @Test
    void claimNextPendingWorksFromARawBackgroundThreadWithNoAmbientTransaction() throws Exception {
        String convId = newConvId();
        commandService.acceptMessage("alice", convId, "req-1", "hello");

        ExecutorService rawThreadPool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ConversationTurn>> result = rawThreadPool.submit(() ->
                    completionService.claimNextPending(convId, UUID.randomUUID().toString()));
            Optional<ConversationTurn> claimed = result.get();

            assertTrue(claimed.isPresent());
            assertEquals(1, claimed.get().getTurnSeq());
            assertEquals(ConversationTurn.Status.PROCESSING,
                    turnRepository.findById(claimed.get().getId()).orElseThrow().getStatus());
        } finally {
            rawThreadPool.shutdown();
        }
    }

    @Test
    void claimNextPendingReturnsEmptyWhenNothingPending() throws Exception {
        String convId = newConvId();

        ExecutorService rawThreadPool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ConversationTurn>> result = rawThreadPool.submit(() ->
                    completionService.claimNextPending(convId, UUID.randomUUID().toString()));
            assertTrue(result.get().isEmpty());
        } finally {
            rawThreadPool.shutdown();
        }
    }
}
