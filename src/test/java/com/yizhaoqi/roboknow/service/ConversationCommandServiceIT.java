package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ConversationCommandServiceIT {

    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationSessionRepository sessionRepository;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationMessageRepository messageRepository;

    private String convId;

    @BeforeEach
    void setUp() {
        convId = "it-cmd-" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);
    }

    @Test
    void duplicateRequestIdReturnsSameTurnSeqWithoutDuplicateRows() {
        String requestId = "req-dup-1";
        TurnAccepted first = commandService.acceptMessage("alice", convId, requestId, "hello");
        TurnAccepted second = commandService.acceptMessage("alice", convId, requestId, "hello");

        assertEquals(first.turnSeq(), second.turnSeq());
        assertEquals(1, turnRepository.findByConvIdAndRequestId(convId, requestId).stream().count());
        assertEquals(1, messageRepository.findByConvIdOrderBySeqAsc(convId).size());
    }

    @Test
    void concurrentAcceptsProduceContiguousTurnSeqsNoGapsNoDuplicates() throws Exception {
        int n = 15;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TurnAccepted>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < n; i++) {
            String requestId = "req-conc-" + i;
            futures.add(pool.submit(() -> {
                go.await();
                return commandService.acceptMessage("alice", convId, requestId, "msg-" + requestId);
            }));
        }
        go.countDown();

        Set<Integer> turnSeqs = new java.util.HashSet<>();
        for (Future<TurnAccepted> f : futures) {
            turnSeqs.add(f.get(10, TimeUnit.SECONDS).turnSeq());
        }
        pool.shutdown();

        assertEquals(n, turnSeqs.size(), "every concurrent accept must get a unique turnSeq");
        assertEquals(Set.of(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15), turnSeqs,
                "turnSeqs must be contiguous 1..n, no gaps");

        long distinctSeqs = messageRepository.findByConvIdOrderBySeqAsc(convId).stream()
                .map(m -> m.getSeq()).distinct().count();
        assertEquals(n, distinctSeqs);
    }

    @Test
    void wrongUserRejected() {
        assertThrows(RuntimeException.class,
                () -> commandService.acceptMessage("mallory", convId, "req-x", "hi"));
    }
}
