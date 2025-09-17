package com.trading.aggregator;

import com.trading.buffer.InHeapRingBuffer;
import com.trading.model.Stats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SymbolAggregatorTest {

    private SymbolAggregator aggregator;

    @BeforeEach
    void setUp() {
        // Use a small in-memory ring buffer for tests
        InHeapRingBuffer ringBuffer = new InHeapRingBuffer(1000);
        aggregator = new SymbolAggregator("TEST", ringBuffer);
        aggregator.markInProgress(); // simulate "running" state
    }

    @Test
    void testSingleBatchProcessing() {
        double[] batch = {10, 20, 30, 40, 50};
        aggregator.enqueueBatch(Arrays.stream(batch).boxed()
                .collect(Collectors.toList()));

        // Run processing
        aggregator.runOneIteration(); // we'll implement a helper to run a single loop

        Stats stats = aggregator.getStats(1); // 10^1 window
        assertEquals(50, stats.getLast());
        assertEquals(10, stats.getMin());
        assertEquals(50, stats.getMax());
        assertEquals(30, stats.getAvg(), 1e-9);
        assertEquals(200, stats.getVariance(), 1e-9);
    }

    @Test
    void testMultipleBatchesProcessing() {
        double[] batch1 = {1, 2, 3};
        double[] batch2 = {4, 5, 6};

        aggregator.enqueueBatch(Arrays.stream(batch1).boxed()
                .collect(Collectors.toList()));
        aggregator.enqueueBatch(Arrays.stream(batch2).boxed()
                .collect(Collectors.toList()));

        aggregator.runOneIteration();

        Stats stats = aggregator.getStats(1);
        assertEquals(6, stats.getLast());
        assertEquals(1, stats.getMin());
        assertEquals(6, stats.getMax());
        assertEquals(3.5, stats.getAvg(), 1e-9);
        assertEquals(2.9166666666666665, stats.getVariance(), 1e-9);
    }

    @Test
    void testEmptyAggregator() {
        Stats stats = aggregator.getStats(1);
        assertTrue(Double.isNaN(stats.getMin()));
        assertTrue(Double.isNaN(stats.getMax()));
        assertTrue(Double.isNaN(stats.getAvg()));
        assertTrue(Double.isNaN(stats.getVariance()));
    }

    @Test
    void testSlidingWindowEviction() {
        // Window size 10^1 = 10
        double[] batch = new double[12];
        for (int i = 0; i < 12; i++) batch[i] = i + 1;

        aggregator.enqueueBatch(Arrays.stream(batch) .boxed()       // box to Double
                .collect(Collectors.toList()));
        aggregator.runOneIteration();

        Stats stats = aggregator.getStats(1);
        // Last 10 elements: 3..12
        assertEquals(12, stats.getLast());
        assertEquals(3, stats.getMin());
        assertEquals(12, stats.getMax());
        assertEquals(7.5, stats.getAvg(), 1e-9);
    }

    @Test
    void testRandomBatches() {
        double[] batch = ThreadLocalRandom.current().doubles(50, 0, 100).toArray();
        aggregator.enqueueBatch(Arrays.stream(batch) .boxed()       // box to Double
                .collect(Collectors.toList()));
        aggregator.runOneIteration();

        Stats stats = aggregator.getStats(2); // 10^2 window (100) not yet full
        assertEquals(batch[batch.length - 1], stats.getLast());
    }

}
