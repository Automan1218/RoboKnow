package com.yizhaoqi.roboknow.client;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把短时间窗口内并发到来的、彼此不同的 embedding 请求合并成一次 API 调用。
 *
 * 查询缓存（HybridSearchService 里的 Redis 缓存）解决的是"同一句 query 被反复问"，
 * 这里解决的是互补的另一半：同一时刻涌入一批彼此不同、都没命中缓存的 query
 * （比如很多用户同时问不同问题），逐个单独调 embedding API 会把外部调用次数
 * 放大成并发数那么多次；这里在一个很短的 debounce 窗口内攒一批，一次 API 调用
 * 打包处理（OpenAI embeddings 接口本身支持单次请求传多条 input）。
 *
 * 触发 flush 的两个条件，谁先到算谁：
 *   1. 攒够 MAX_BATCH_SIZE 条
 *   2. 从第一条进队列起过了 DEBOUNCE_MS 还没攒够，超时也要发，不能让早到的请求
 *      等太久
 */
@Component
public class EmbeddingRequestBatcher {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingRequestBatcher.class);

    @Value("${embedding.batch.max-size:50}")
    private int maxBatchSize;

    @Value("${embedding.batch.debounce-ms:20}")
    private long debounceMs;

    private final EmbeddingClient embeddingClient;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "embedding-batcher");
                t.setDaemon(true);
                return t;
            });

    private final Object lock = new Object();
    private List<String> pendingTexts = new ArrayList<>();
    private List<CompletableFuture<float[]>> pendingFutures = new ArrayList<>();
    private ScheduledFuture<?> scheduledFlush;

    public EmbeddingRequestBatcher(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    public CompletableFuture<float[]> requestEmbedding(String text) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        boolean flushNow;

        synchronized (lock) {
            pendingTexts.add(text);
            pendingFutures.add(future);
            flushNow = pendingTexts.size() >= maxBatchSize;

            if (flushNow && scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            } else if (!flushNow && scheduledFlush == null) {
                scheduledFlush = scheduler.schedule(this::flush, debounceMs, TimeUnit.MILLISECONDS);
            }
        }

        if (flushNow) {
            flush();
        }
        return future;
    }

    private void flush() {
        List<String> texts;
        List<CompletableFuture<float[]>> futures;

        synchronized (lock) {
            if (pendingTexts.isEmpty()) {
                return;
            }
            texts = pendingTexts;
            futures = pendingFutures;
            pendingTexts = new ArrayList<>();
            pendingFutures = new ArrayList<>();
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
        }

        logger.debug("批量 embedding: 合并 {} 个并发请求为 1 次 API 调用", texts.size());
        try {
            List<float[]> vectors = embeddingClient.embed(texts);
            for (int i = 0; i < futures.size(); i++) {
                if (i < vectors.size()) {
                    futures.get(i).complete(vectors.get(i));
                } else {
                    futures.get(i).completeExceptionally(
                            new IllegalStateException("批量向量结果数量少于请求数量"));
                }
            }
        } catch (Exception e) {
            for (CompletableFuture<float[]> f : futures) {
                f.completeExceptionally(e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
