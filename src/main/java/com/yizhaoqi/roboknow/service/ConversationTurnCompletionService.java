package com.yizhaoqi.roboknow.service;

import com.yizhaoqi.roboknow.model.ConversationMessage;
import com.yizhaoqi.roboknow.repository.ConversationMessageRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * turn 完成/失败的唯一落库入口。“先提交、后推送”里的“提交”就是这个类。
 * completeIfOwned 用 attemptToken 做条件更新——过期 worker（比如重启后残留的旧线程）
 * 即使还在跑也提交不了，防止重复写入。
 */
@Service
public class ConversationTurnCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnCompletionService.class);

    private final ConversationTurnRepository turnRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationTurnCompletionService(ConversationTurnRepository turnRepository,
                                              ConversationMessageRepository messageRepository) {
        this.turnRepository = turnRepository;
        this.messageRepository = messageRepository;
    }

    /** @return true 表示本次提交成功拿到了权属；false 表示 attemptToken 已过期，调用方不应再推送结果给用户。 */
    @Transactional
    public boolean complete(Long turnId, String attemptToken, String convId, int turnSeq, String finalAnswer) {
        int updated = turnRepository.completeIfOwned(turnId, attemptToken, finalAnswer);
        if (updated == 0) {
            logger.warn("completeIfOwned no-op (stale attemptToken?) turnId={} convId={} turnSeq={}",
                    turnId, convId, turnSeq);
            return false;
        }
        ConversationMessage assistantRow = new ConversationMessage();
        assistantRow.setConvId(convId);
        assistantRow.setSeq(turnSeq * 2 + 1);
        assistantRow.setRole("assistant");
        assistantRow.setContent(finalAnswer);
        assistantRow.setCreatedAt(LocalDateTime.now());
        messageRepository.save(assistantRow);
        return true;
    }

    @Transactional
    public void markFailed(Long turnId, String attemptToken, String errorCode) {
        int updated = turnRepository.failIfOwned(turnId, attemptToken, errorCode);
        if (updated == 0) {
            logger.warn("failIfOwned no-op (stale attemptToken?) turnId={}", turnId);
        }
    }
}
