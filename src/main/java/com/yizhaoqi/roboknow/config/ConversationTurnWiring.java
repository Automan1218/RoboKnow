package com.yizhaoqi.roboknow.config;

import com.yizhaoqi.roboknow.agent.ReactAgentService;
import com.yizhaoqi.roboknow.handler.WebSocketSessionRegistry;
import com.yizhaoqi.roboknow.model.ConversationSession;
import com.yizhaoqi.roboknow.model.ConversationTurn;
import com.yizhaoqi.roboknow.repository.ConversationSessionRepository;
import com.yizhaoqi.roboknow.repository.ConversationTurnRepository;
import com.yizhaoqi.roboknow.service.ConversationTurnCompletionService;
import com.yizhaoqi.roboknow.service.ConversationTurnDispatcher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 把 dispatcher 的“串行执行一批 turn”职责，和 ReactAgentService 的“怎么处理一个 turn”职责接起来。
 * 放在单独的 wiring 类，避免 ConversationTurnDispatcher（偏基础设施）反向依赖 ReactAgentService（偏业务）。
 *
 * 另外承担两个恢复职责，弥补进程内 dispatcher 没有分布式租约/reconciliation 服务的缺口：
 *   1. 启动时把上次崩溃残留的 PROCESSING turn（回答还没提交）重置回 PENDING；
 *   2. 周期性扫一遍还有 PENDING turn 的 convId 并重新 submit——兜住 dispatcher.submit() 里
 *      “判定没有 PENDING 了”和“释放运行标志”之间那个极窄的漏 wake 窗口。
 */
@Component
public class ConversationTurnWiring {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnWiring.class);

    private final ConversationTurnDispatcher dispatcher;
    private final ConversationTurnRepository turnRepository;
    private final ConversationSessionRepository sessionRepository;
    private final ConversationTurnCompletionService completionService;
    private final ReactAgentService reactAgentService;
    private final WebSocketSessionRegistry sessionRegistry;

    public ConversationTurnWiring(ConversationTurnDispatcher dispatcher,
                                   ConversationTurnRepository turnRepository,
                                   ConversationSessionRepository sessionRepository,
                                   ConversationTurnCompletionService completionService,
                                   ReactAgentService reactAgentService,
                                   WebSocketSessionRegistry sessionRegistry) {
        this.dispatcher = dispatcher;
        this.turnRepository = turnRepository;
        this.sessionRepository = sessionRepository;
        this.completionService = completionService;
        this.reactAgentService = reactAgentService;
        this.sessionRegistry = sessionRegistry;
    }

    @PostConstruct
    void wire() {
        dispatcher.setProcessor(this::drainAllPending);

        int recovered = turnRepository.resetOrphanedProcessingTurnsToPending();
        if (recovered > 0) {
            logger.warn("Recovered {} PROCESSING turn(s) orphaned by a previous crash, reset to PENDING", recovered);
        }
        // 重置后立即触发一轮扫描，不用等第一个周期定时器
        wakeUpConvIdsWithPendingTurns();
    }

    /** 每 5 秒兜底扫描一次：正常路径下这里应该什么都不用做，纯粹是漏 wake 场景的安全网。 */
    @Scheduled(fixedDelay = 5000)
    void wakeUpConvIdsWithPendingTurns() {
        List<String> convIds = turnRepository.findDistinctConvIdsWithPendingTurns();
        for (String convId : convIds) {
            dispatcher.submit(convId);
        }
    }

    private void drainAllPending(String convId) {
        while (true) {
            Optional<ConversationTurn> next = turnRepository
                    .findFirstByConvIdAndStatusOrderByTurnSeqAsc(convId, ConversationTurn.Status.PENDING);
            if (next.isEmpty()) return;

            ConversationTurn turn = next.get();
            String attemptToken = UUID.randomUUID().toString();
            int claimed = turnRepository.claimForProcessing(turn.getId(), attemptToken);
            if (claimed == 0) {
                // 理论上不该有并发 claim（同一 convId 只有一个 drain 在跑），但保底跳过防止死循环
                continue;
            }

            String userId = resolveUserId(turn);
            Optional<WebSocketSession> sessionOpt = sessionRegistry.get(userId);
            if (sessionOpt.isEmpty()) {
                logger.warn("No live WebSocket session to push turn result, marking FAILED. convId={} turnSeq={}",
                        convId, turn.getTurnSeq());
                completionService.markFailed(turn.getId(), attemptToken, "NO_LIVE_SESSION");
                continue;
            }

            try {
                reactAgentService.processTurn(userId, convId, turn.getTurnSeq(),
                        turn.getRequestId(), turn.getId(), attemptToken, turn.getUserContent(),
                        sessionOpt.get());
            } catch (Exception e) {
                logger.error("Unhandled exception processing turn id={}: {}", turn.getId(), e.getMessage(), e);
                completionService.markFailed(turn.getId(), attemptToken, "UNHANDLED_EXCEPTION");
            }
        }
    }

    private String resolveUserId(ConversationTurn turn) {
        return sessionRepository.findById(turn.getConvId())
                .map(ConversationSession::getUserId)
                .orElseThrow(() -> new IllegalStateException("Session missing for convId=" + turn.getConvId()));
    }
}
