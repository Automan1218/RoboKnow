package com.yizhaoqi.roboknow.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingRequestBatcherTest {

    private EmbeddingClient embeddingClient;
    private EmbeddingRequestBatcher batcher;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        batcher = new EmbeddingRequestBatcher(embeddingClient);
        ReflectionTestUtils.setField(batcher, "maxBatchSize", 50);
        ReflectionTestUtils.setField(batcher, "debounceMs", 20L);
    }

    @Test
    void concurrentDistinctRequestsWithinDebounceWindowMergeIntoOneApiCall() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(t -> new float[]{t.length()}) // 用文本长度当作向量占位，方便按调用顺序断言
                    .toList();
        });

        int n = 8;
        CountDownLatch ready = new CountDownLatch(n);
        List<CompletableFuture<float[]>> futures = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> {
                    CompletableFuture<float[]> f = batcher.requestEmbedding("query-" + i);
                    ready.countDown();
                    return f;
                })
                .toList();

        for (int i = 0; i < n; i++) {
            float[] result = futures.get(i).get(5, TimeUnit.SECONDS);
            assertEquals(("query-" + i).length(), (int) result[0], "结果必须和调用顺序一一对应，不能错位");
        }

        assertEquals(1, callCount.get(), "同一个 debounce 窗口内的并发请求应该被合并成 1 次 API 调用");
        verify(embeddingClient, times(1)).embed(anyList());
    }

    @Test
    void batchFlushesImmediatelyOnceMaxSizeReached() throws Exception {
        ReflectionTestUtils.setField(batcher, "maxBatchSize", 3);
        ReflectionTestUtils.setField(batcher, "debounceMs", 10_000L); // 故意设很长，确保是"攒够数量"触发而不是超时

        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[]{1f}).toList();
        });

        CompletableFuture<float[]> f1 = batcher.requestEmbedding("a");
        CompletableFuture<float[]> f2 = batcher.requestEmbedding("b");
        CompletableFuture<float[]> f3 = batcher.requestEmbedding("c");

        // 达到 maxBatchSize=3 应该立刻 flush，不需要等 10 秒的 debounce
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
        f3.get(2, TimeUnit.SECONDS);

        verify(embeddingClient, times(1)).embed(anyList());
    }

    @Test
    void apiFailurePropagatesToAllPendingCallersInBatch() {
        RuntimeException boom = new RuntimeException("embedding API 挂了");
        when(embeddingClient.embed(anyList())).thenThrow(boom);

        CompletableFuture<float[]> f1 = batcher.requestEmbedding("x");
        CompletableFuture<float[]> f2 = batcher.requestEmbedding("y");

        ExecutionException e1 = assertThrows(ExecutionException.class, () -> f1.get(2, TimeUnit.SECONDS));
        ExecutionException e2 = assertThrows(ExecutionException.class, () -> f2.get(2, TimeUnit.SECONDS));
        assertEquals(boom, e1.getCause());
        assertEquals(boom, e2.getCause());
    }

    @Test
    void separateBatchesDoNotBlockEachOther() throws Exception {
        when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[]{t.length()}).toList();
        });

        float[] r1 = batcher.requestEmbedding("first").get(5, TimeUnit.SECONDS);
        assertArrayEquals(new float[]{5f}, r1);

        // debounce 窗口早已过去，这应该是独立的第二批
        Thread.sleep(50);
        float[] r2 = batcher.requestEmbedding("second-batch").get(5, TimeUnit.SECONDS);
        assertArrayEquals(new float[]{12f}, r2);

        verify(embeddingClient, times(2)).embed(anyList());
    }
}
