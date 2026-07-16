package com.yizhaoqi.roboknow.repository;

import com.yizhaoqi.roboknow.model.ConversationSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @SpringBootTest 而非 @DataJpaTest：@DataJpaTest 会把整个测试方法包在一个不提交的外层事务里，
 * 后台线程用 PESSIMISTIC_WRITE 去锁同一行时会因为外层事务从未提交而永久等锁超时——
 * 这个测试的本质就是验证多个真实并发事务的行为，必须让每个线程的事务真正提交。
 */
@SpringBootTest
@ActiveProfiles("dev")
class ConversationSessionTurnSeqIT {

    @Autowired
    private ConversationSessionRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void concurrentAllocationProducesNoDuplicatesAndReachesExpectedTotal() throws Exception {
        String convId = "it-tseq-" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        repository.saveAndFlush(session);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> allocated = new ConcurrentLinkedQueue<>();
        TransactionTemplate tx = new TransactionTemplate(txManager);

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException ignored) {}
                Integer result = tx.execute(status -> {
                    ConversationSession s = repository.lockForUpdate(convId).orElseThrow();
                    int allocatedSeq = s.getNextTurnSeq() + 1;
                    s.setNextTurnSeq(allocatedSeq);
                    repository.save(s);
                    return allocatedSeq;
                });
                allocated.add(result);
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(threads, allocated.size());
        assertEquals(threads, Set.copyOf(allocated).size(), "no two threads may receive the same turnSeq");

        ConversationSession finalState = repository.findById(convId).orElseThrow();
        assertEquals(threads, finalState.getNextTurnSeq());
    }
}
