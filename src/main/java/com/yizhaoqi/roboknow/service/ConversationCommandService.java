package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.exception.CustomException;
import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 接收用户消息的唯一入口：同步短事务里分配 turnSeq、写 PENDING turn 和 user 消息。
 * 事务提交后才允许调用方返回 accepted——DB 是消息是否被接受的唯一真相源。
 */
@Service
public class ConversationCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCommandService.class);

    private final ConversationSessionRepository sessionRepository;
    private final ConversationTurnRepository turnRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationCommandService(ConversationSessionRepository sessionRepository,
                                       ConversationTurnRepository turnRepository,
                                       ConversationMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public TurnAccepted acceptMessage(String userId, String convId, String requestId, String userMessage) {
        // 幂等：同一 (convId, requestId) 重复提交返回已有 turn，不重复分配 seq / 不重复插入消息
        Optional<ConversationTurn> existing = turnRepository.findByConvIdAndRequestId(convId, requestId);
        if (existing.isPresent()) {
            ConversationTurn t = existing.get();
            logger.debug("Duplicate requestId={} for convId={}, returning existing turnSeq={}",
                    requestId, convId, t.getTurnSeq());
            return new TurnAccepted(t.getTurnSeq(), requestId, t.getStatus());
        }

        ConversationSession session = sessionRepository.lockForUpdate(convId)
                .orElseThrow(() -> new CustomException("Session not found", HttpStatus.NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        }

        int turnSeq = session.getNextTurnSeq() + 1;
        session.setNextTurnSeq(turnSeq);
        sessionRepository.save(session);

        ConversationTurn turn = new ConversationTurn();
        turn.setConvId(convId);
        turn.setTurnSeq(turnSeq);
        turn.setRequestId(requestId);
        turn.setUserContent(userMessage);
        turn.setStatus(ConversationTurn.Status.PENDING);
        turn.setReceivedAt(LocalDateTime.now());
        turnRepository.save(turn);

        ConversationMessage userRow = new ConversationMessage();
        userRow.setConvId(convId);
        userRow.setSeq(turnSeq * 2);
        userRow.setRole("user");
        userRow.setContent(userMessage);
        userRow.setCreatedAt(LocalDateTime.now());
        messageRepository.save(userRow);

        logger.info("Accepted turn convId={} turnSeq={} requestId={}", convId, turnSeq, requestId);
        return new TurnAccepted(turnSeq, requestId, ConversationTurn.Status.PENDING);
    }
}
