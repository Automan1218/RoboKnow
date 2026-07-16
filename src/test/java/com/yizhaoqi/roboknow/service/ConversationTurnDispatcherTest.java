package com.yizhaoqi.roboknow.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnDispatcherTest {

    @Test
    void neverRunsTwoDrainsForSameConvIdConcurrently() throws Exception {
        var dispatcher = new ConversationTurnDispatcher(new SimpleAsyncTaskExecutor());
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger totalRuns = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(1);

        dispatcher.setProcessor(convId -> {
            int c = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(m -> Math.max(m, c));
            try {
                Thread.sleep(100); // simulate work, wide enough window for a race to show up
                totalRuns.incrementAndGet();
            } catch (InterruptedException ignored) {
            } finally {
                concurrentCount.decrementAndGet();
                if (totalRuns.get() >= 1) done.countDown();
            }
        });

        // fire 10 concurrent submits for the SAME convId
        for (int i = 0; i < 10; i++) {
            dispatcher.submit("conv-race");
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(200); // let any stray second run surface
        assertEquals(1, maxConcurrent.get(), "must never run two drains for the same convId concurrently");
    }

    @Test
    void differentConvIdsRunInParallel() throws Exception {
        var dispatcher = new ConversationTurnDispatcher(new SimpleAsyncTaskExecutor());
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicBoolean sawOverlap = new AtomicBoolean(false);

        dispatcher.setProcessor(convId -> {
            bothStarted.countDown();
            try {
                boolean overlapped = bothStarted.await(2, TimeUnit.SECONDS);
                if (overlapped) sawOverlap.set(true);
            } catch (InterruptedException ignored) {}
        });

        dispatcher.submit("conv-a");
        dispatcher.submit("conv-b");

        Thread.sleep(500);
        assertTrue(sawOverlap.get(), "different convIds must be able to run concurrently");
    }

    @Test
    void submitWithoutProcessorConfiguredDoesNotThrow() throws Exception {
        var dispatcher = new ConversationTurnDispatcher(new SimpleAsyncTaskExecutor());
        assertDoesNotThrow(() -> dispatcher.submit("conv-no-processor"));
        Thread.sleep(100);
    }
}
