package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @Transactional：这里不测并发（那是 ConversationCommandServiceIT 的职责），单线程内调用
 * @Modifying 查询需要一个活跃事务，Spring Test 的每方法事务顺带提供了自动回滚，不污染 dev 库。
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class ConversationTurnCompletionServiceIT {

    @Autowired private ConversationTurnCompletionService completionService;
    @Autowired private ConversationCommandService commandService;
    @Autowired private ConversationTurnRepository turnRepository;
    @Autowired private ConversationMessageRepository messageRepository;
    @Autowired private ConversationSessionRepository sessionRepository;

    private String convId;

    @BeforeEach
    void setUp() {
        convId = "it-cmpl-" + UUID.randomUUID().toString().substring(0, 8);
        ConversationSession session = new ConversationSession();
        session.setId(convId);
        session.setUserId("alice");
        sessionRepository.save(session);
    }

    @Test
    void completeWritesAssistantMessageAndMarksTurnComplete() {
        TurnAccepted accepted = commandService.acceptMessage("alice", convId, "req-1", "hi");
        ConversationTurn turn = turnRepository.findByConvIdAndRequestId(convId, "req-1").orElseThrow();
        String attemptToken = UUID.randomUUID().toString();
        turnRepository.claimForProcessing(turn.getId(), attemptToken);

        boolean ok = completionService.complete(turn.getId(), attemptToken, convId, accepted.turnSeq(), "hi back");
        assertTrue(ok);

        ConversationTurn reloaded = turnRepository.findById(turn.getId()).orElseThrow();
        assertEquals(ConversationTurn.Status.COMPLETE, reloaded.getStatus());
        assertEquals("hi back", reloaded.getAssistantContent());

        var messages = messageRepository.findByConvIdOrderBySeqAsc(convId);
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals(accepted.turnSeq() * 2, messages.get(0).getSeq());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals(accepted.turnSeq() * 2 + 1, messages.get(1).getSeq());
    }

    @Test
    void completeWithStaleAttemptTokenIsNoOp() {
        TurnAccepted accepted = commandService.acceptMessage("alice", convId, "req-2", "hi");
        ConversationTurn turn = turnRepository.findByConvIdAndRequestId(convId, "req-2").orElseThrow();
        turnRepository.claimForProcessing(turn.getId(), "real-token");

        boolean ok = completionService.complete(turn.getId(), "stale-token", convId, accepted.turnSeq(), "should not land");
        assertFalse(ok);

        var messages = messageRepository.findByConvIdOrderBySeqAsc(convId);
        assertEquals(1, messages.size(), "only the user message should exist, assistant write must be rejected");
    }

    @Test
    void markFailedTransitionsStatusWithoutWritingAssistantMessage() {
        commandService.acceptMessage("alice", convId, "req-3", "hi");
        ConversationTurn turn = turnRepository.findByConvIdAndRequestId(convId, "req-3").orElseThrow();
        String attemptToken = UUID.randomUUID().toString();
        turnRepository.claimForProcessing(turn.getId(), attemptToken);

        completionService.markFailed(turn.getId(), attemptToken, "LLM_TIMEOUT");

        ConversationTurn reloaded = turnRepository.findById(turn.getId()).orElseThrow();
        assertEquals(ConversationTurn.Status.FAILED, reloaded.getStatus());
        assertEquals("LLM_TIMEOUT", reloaded.getErrorCode());
        assertEquals(1, messageRepository.findByConvIdOrderBySeqAsc(convId).size());
    }
}
