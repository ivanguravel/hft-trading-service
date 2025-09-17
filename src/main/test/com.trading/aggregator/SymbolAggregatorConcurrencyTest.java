package com.trading.aggregator;

import com.trading.buffer.InHeapRingBuffer;
import com.trading.model.Stats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SymbolAggregatorConcurrencyTest {

    private SymbolAggregator aggregator;

    @BeforeEach
    void setUp() {
        InHeapRingBuffer ringBuffer = new InHeapRingBuffer(10_000);
        aggregator = new SymbolAggregator("MULTI", ringBuffer);
        aggregator.markInProgress(); // simulate "running" state
    }

    @Test
    void testConcurrentBatchEnqueue() throws InterruptedException {
        int numThreads = 8;
        int batchesPerThread = 50;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                for (int i = 0; i < batchesPerThread; i++) {
                    List<Double> batch = Arrays.stream(ThreadLocalRandom.current().doubles(20, 0, 100).toArray()) .boxed()       // box to Double
                            .collect(Collectors.toList());
                    aggregator.enqueueBatch(batch);
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Now process all batches once
        aggregator.runOneIteration();

        Stats stats = aggregator.getStats(1); // 10^1 window
        assertNotNull(stats);
        assertFalse(Double.isNaN(stats.getLast()));
        assertFalse(Double.isNaN(stats.getAvg()));
        assertFalse(Double.isNaN(stats.getVariance()));
        assertFalse(Double.isNaN(stats.getMin()));
        assertFalse(Double.isNaN(stats.getMax()));

        // Check last value is one of the enqueued values
        assertTrue(stats.getLast() >= 0 && stats.getLast() <= 100);
    }
}
