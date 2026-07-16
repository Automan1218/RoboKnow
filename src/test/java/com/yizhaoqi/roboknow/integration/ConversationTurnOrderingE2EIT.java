package com.yizhaoqi.roboknow.integration;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import com.yizhaoqi.roboknow.service.ConversationCommandService;
import com.yizhaoqi.roboknow.service.ConversationTurnCompletionService;
import com.yizhaoqi.roboknow.service.ConversationTurnDispatcher;
import com.yizhaoqi.roboknow.service.TurnAccepted;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阶段一的验收核心：同一 convId 连续到达 3 条消息、不等前一条处理完就发下一条，
 * 验证最终 conversation_messages 顺序严格是 U1,A1,U2,A2,U3,A3，且同一 convId 的
 * turn 处理从不并发重叠。用 fake processor 隔离掉真实 LLM/WebSocket 依赖，
 * 聚焦本阶段要证明的顺序/并发不变量本身。
 */
/**
 * @DirtiesContext：本测试用 dispatcher.setProcessor() 替换成 fake processor 来隔离真实
 * LLM/WebSocket 依赖。dispatcher 是单例 bean，若不重建上下文，这个替换会残留到同一 JVM 里
 * 后续复用该上下文的测试类，导致它们的 turn 处理被这个 fake processor 悄悄接管。
 */
@SpringBootTest
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConversationTurnOrderingE2EIT {

    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationTurnDispatcher dispatcher;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationTurnCompletionService completionService;
    @Autowired private ConversationMessageRepository messageRepository;
    @Autowired private ConversationSessionRepository sessionRepository;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    void threeRapidMessagesToSameConvProduceStrictlyOrderedHistoryNoOverlap() throws Exception {
        String convId = "it-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);

        AtomicInteger concurrentProcessing = new AtomicInteger(0);
        AtomicInteger maxConcurrentProcessing = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(3);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        dispatcher.setProcessor(convId2 -> {
            while (true) {
                // 这个 processor 跑在 turnWorkerExecutor 的裸线程上，没有 Spring 事务上下文；
                // claimForProcessing 是 @Modifying 查询，必须显式开事务才能执行——跟
                // ConversationTurnWiring.@PostConstruct 直接调 repository 会炸的是同一个坑。
                Optional<ConversationTurn> next = turnRepository.findFirstByConvIdAndStatusOrderByTurnSeqAsc(
                        convId2, ConversationTurn.Status.PENDING);
                if (next.isEmpty()) return;
                ConversationTurn turn = next.get();
                String attemptToken = UUID.randomUUID().toString();
                Integer claimed = tx.execute(status ->
                        turnRepository.claimForProcessing(turn.getId(), attemptToken));
                if (claimed == null || claimed == 0) continue;

                int c = concurrentProcessing.incrementAndGet();
                maxConcurrentProcessing.updateAndGet(m -> Math.max(m, c));
                try {
                    Thread.sleep(150); // simulate "slow LLM turn"
                    completionService.complete(turn.getId(), attemptToken, convId2, turn.getTurnSeq(),
                            "answer-to-" + turn.getUserContent());
                } catch (InterruptedException ignored) {
                } finally {
                    concurrentProcessing.decrementAndGet();
                    allDone.countDown();
                }
            }
        });

        // 三条消息几乎同时到达，不等前一条处理完
        TurnAccepted t1 = commandService.acceptMessage("alice", convId, "req-1", "first question");
        dispatcher.submit(convId);
        TurnAccepted t2 = commandService.acceptMessage("alice", convId, "req-2", "second question");
        dispatcher.submit(convId);
        TurnAccepted t3 = commandService.acceptMessage("alice", convId, "req-3", "third question");
        dispatcher.submit(convId);

        assertEquals(1, t1.turnSeq());
        assertEquals(2, t2.turnSeq());
        assertEquals(3, t3.turnSeq());

        // 宽松超时：同一个 dev 库里可能还有其它 IT 测试遗留的 PENDING turn，启动时的恢复扫描
        // （ConversationTurnWiring）会把它们也提交给同一个共享线程池，跟这里的 fake processor
        // 抢线程——这是恢复机制本身在正常工作的副作用，不是这条测试要验证的东西，给足余量避免抖动。
        assertTrue(allDone.await(30, TimeUnit.SECONDS), "all three turns must complete");
        assertEquals(1, maxConcurrentProcessing.get(), "turns for the same convId must never overlap");

        List<com.yizhaoqi.roboknow.model.ConversationMessage> history =
                messageRepository.findByConvIdOrderBySeqAsc(convId);
        assertEquals(6, history.size());
        assertEquals("user", history.get(0).getRole());
        assertEquals("first question", history.get(0).getContent());
        assertEquals("assistant", history.get(1).getRole());
        assertEquals("answer-to-first question", history.get(1).getContent());
        assertEquals("user", history.get(2).getRole());
        assertEquals("second question", history.get(2).getContent());
        assertEquals("assistant", history.get(3).getRole());
        assertEquals("user", history.get(4).getRole());
        assertEquals("third question", history.get(4).getContent());
        assertEquals("assistant", history.get(5).getRole());

        // seq = turnSeq*2 / turnSeq*2+1，turnSeq 从 1 开始（nextTurnSeq 初始 0），所以起点是
        // t1.turnSeq()*2，不是 0——只断言严格连续递增，不硬编码绝对起点。
        int expectedStart = t1.turnSeq() * 2;
        for (int i = 0; i < history.size(); i++) {
            assertEquals(expectedStart + i, history.get(i).getSeq(), "seq must be strictly contiguous");
        }
    }
}
