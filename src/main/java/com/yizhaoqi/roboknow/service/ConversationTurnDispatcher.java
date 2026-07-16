package com.yizhaoqi.roboknow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保证同一 convId 的 turn 处理严格串行，不同 convId 并行——进程内版本的"每会话运行租约"。
 * 崩溃恢复不依赖这个 Map 里的状态：重启后 PENDING/PROCESSING 的 turn 仍在数据库里，
 * 下一次任意消息触发 submit() 时会被同一个 drain 循环重新捡起来处理。
 */
@Component
public class ConversationTurnDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ConversationTurnDispatcher.class);

    @FunctionalInterface
    public interface TurnBatchProcessor {
        /** 处理该 convId 当前所有 PENDING turn，直到没有为止（内部自己 loop）。不得抛出未捕获异常。 */
        void drainAllPending(String convId);
    }

    private final ConcurrentHashMap<String, AtomicBoolean> draining = new ConcurrentHashMap<>();
    private final Executor executor;
    private volatile TurnBatchProcessor processor;

    public ConversationTurnDispatcher(@Qualifier("turnWorkerExecutor") Executor executor) {
        this.executor = executor;
    }

    public void setProcessor(TurnBatchProcessor processor) {
        this.processor = processor;
    }

    /**
     * 唤醒该 convId 的处理。若已有 drain 在跑，直接返回——那个 drain 循环负责把新出现的
     * PENDING turn 一并处理掉（drainAllPending 必须 loop 到确认没有 PENDING 才返回）。
     */
    public void submit(String convId) {
        AtomicBoolean flag = draining.computeIfAbsent(convId, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    TurnBatchProcessor p = processor;
                    if (p == null) {
                        logger.warn("No processor configured, skipping convId={}", convId);
                        return;
                    }
                    p.drainAllPending(convId);
                } finally {
                    flag.set(false);
                }
            });
        }
        // else：已有 drain 在跑，不需要第二个线程
    }
}
