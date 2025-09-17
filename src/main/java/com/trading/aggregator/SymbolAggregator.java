package com.trading.aggregator;

import com.trading.buffer.RingBuffer;
import com.trading.model.Batch;
import com.trading.model.Stats;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoublePredicate;

public class SymbolAggregator implements Runnable {

    private static final short AMOUNT_OF_BATCHES_PER_SYMBOL = 9;

    private final String symbol;
    private final RingBuffer ringBuffer;
    private final int capacity;
    private final BatchQueue queue;
    private final AtomicLong globalIndex = new AtomicLong(0);

    private final double[] sum = new double[9];
    private final double[] sumSq = new double[9];
    private final int[] windowSize = new int[9];
    private final int[] count = new int[9];
    private final Deque<long[]>[] minDeque = new ConcurrentLinkedDeque[AMOUNT_OF_BATCHES_PER_SYMBOL];
    private final Deque<long[]>[] maxDeque = new ConcurrentLinkedDeque[AMOUNT_OF_BATCHES_PER_SYMBOL];
    private volatile AtomicReference<Double> lastValue = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Stats>[] snapshots = new AtomicReference[AMOUNT_OF_BATCHES_PER_SYMBOL];
    private AtomicBoolean running = new AtomicBoolean(false);

    public SymbolAggregator(String symbol, RingBuffer ringBuffer) {
        this.symbol = symbol;
        this.ringBuffer = ringBuffer;
        this.capacity = (int) ringBuffer.capacity();
        this.queue = new BatchQueue(65_536);

        for (int k = 1; k <= 8; k++) {
            windowSize[k] = (int) Math.pow(10, k);
            minDeque[k] = new ConcurrentLinkedDeque<>();
            maxDeque[k] = new ConcurrentLinkedDeque<>();
            snapshots[k] = new AtomicReference<>(
                    new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0)
            );
        }

    }

    public void enqueueBatch(List<Double> values) {
        Batch batch = new Batch(values);
        if (!queue.offer(batch)) {
            throw new IllegalStateException("Batch queue overflow for " + symbol);
        }
    }

    public Stats getStats(int k) {
        return snapshots[k].get();
    }


    public boolean markInProgress() {
        return running.compareAndSet(running.get(), true);
    }

    public void shutdown() {
        running.compareAndSet(running.get(), false);
    }

    public void run() {
        while (running.get()) {
            while (!queue.isEmpty()) {
                Batch batch = queue.poll();
                if (batch == null) {
                    continue;
                }

                for (double value : batch.getValues()) {
                    processValue(value);
                }
                updateSnapshots();
            }
        }
    }



    /**
     * Processes a single incoming trading price and updates all rolling statistics
     * (min, max, average, variance) for each configured sliding window size (10^1 ... 10^8).
     *
     * <p>This method is invoked by the worker thread whenever a new value is received.
     * It maintains state in constant time for every sliding window:
     * <ul>
     *   <li>Stores the value in the ring buffer, indexed by a global sequence number.</li>
     *   <li>Updates the "last" observed value.</li>
     *   <li>For each window size (10^k):
     *     <ul>
     *       <li>Adds the new value to rolling sums (sum and sum of squares).</li>
     *       <li>If the window is full, evicts the oldest value and subtracts its contribution.</li>
     *       <li>Maintains monotonic deques for min and max in amortized O(1).</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>Complexity:
     * <ul>
     *   <li>Insertion per value: O(1) amortized for each window.</li>
     *   <li>Maintains min/max in O(1) amortized via monotonic queues.</li>
     * </ul>
     *
     * <p>Thread-safety:
     * This method is intended to be called by a single worker thread per symbol.
     * No external synchronization is required because only one thread mutates
     * the state of each {@code SymbolAggregator}.
     *
     * @param value the new trading price to process
     */
    private void processValue(double value) {
        long index = globalIndex.getAndIncrement();

        // Store value in the ring buffer (for later eviction).
        ringBuffer.set(index, value);

        // Update the last seen value.
        lastValue.set(value);

        // Update all configured windows (10^1 ... 10^8).
        for (int k = 1; k <= 8; k++) {
            processForWindow(k, index, value);
        }
    }

    /**
     * Update statistics for a single sliding window size 10^k.
     */
    private void processForWindow(int k, long index, double value) {
        int window = windowSize[k];

        // Update rolling sums for mean/variance
        sum[k] += value;
        sumSq[k] += value * value;

        if (count[k] < window) {
            // Window not full → just increase count
            count[k]++;
        } else {
            // Window full → evict oldest
            evictOldValue(k, index - window);
        }

        // Maintain monotonic deques for min and max
        long rawBits = Double.doubleToRawLongBits(value);
        updateDeque(minDeque[k], index, rawBits, v -> v > value);
        updateDeque(maxDeque[k], index, rawBits, v -> v < value);
    }

    /**
     * Remove the oldest value from rolling sums and deques.
     */
    private void evictOldValue(int k, long outIndex) {
        double outValue = ringBuffer.get(outIndex);

        sum[k] -= outValue;
        sumSq[k] -= outValue * outValue;

        if (!minDeque[k].isEmpty() && minDeque[k].peekFirst()[0] == outIndex) {
            minDeque[k].pollFirst();
        }
        if (!maxDeque[k].isEmpty() && maxDeque[k].peekFirst()[0] == outIndex) {
            maxDeque[k].pollFirst();
        }
    }

    /**
     * Update a monotonic deque: remove obsolete tail values
     * according to comparator, then push the new value.
     *
     * @param deque    deque of [index, rawDoubleBits]
     * @param index    index of the new value
     * @param rawBits  new value in raw double bits
     * @param shouldRemove predicate deciding if tail must be removed
     */
    private void updateDeque(Deque<long[]> deque,
                             long index,
                             long rawBits,
                             DoublePredicate shouldRemove) {
        while (!deque.isEmpty()
                && shouldRemove.test(Double.longBitsToDouble(deque.peekLast()[1]))) {
            deque.pollLast();
        }
        deque.addLast(new long[]{index, rawBits});
    }

    /**
     * Update snapshots for all windows (latest statistics).
     */
    private void updateSnapshots() {
        long endIndex = globalIndex.get() - 1;
        for (int k = 1; k <= 8; k++) {
            updateSnapshotForWindow(k, endIndex);
        }
    }

    /**
     * Update snapshot for one sliding window.
     */
    private void updateSnapshotForWindow(int k, long endIndex) {
        int countInWindow = count[k];
        if (countInWindow == 0) {
            snapshots[k].set(Stats.empty());
            return;
        }

        long startIndex = Math.max(0L, endIndex - (windowSize[k] - 1));
        evictOutdated(minDeque[k], startIndex);
        evictOutdated(maxDeque[k], startIndex);

        double avg = sum[k] / countInWindow;
        double var = (sumSq[k] / countInWindow) - avg * avg;
        double min = peekValue(minDeque[k]);
        double max = peekValue(maxDeque[k]);

        snapshots[k].set(new Stats(min, max, lastValue.get(), avg, var, countInWindow));
    }

    /**
     * Remove outdated values (indices < startIndex) from a deque.
     */
    private void evictOutdated(Deque<long[]> deque, long startIndex) {
        while (!deque.isEmpty() && deque.peekFirst()[0] < startIndex) {
            deque.pollFirst();
        }
    }

    /**
     * Peek the first value from a deque (min or max).
     *
     * @return the decoded double, or NaN if empty
     */
    private double peekValue(Deque<long[]> deque) {
        return deque.isEmpty()
                ? Double.NaN
                : Double.longBitsToDouble(deque.peekFirst()[1]);
    }

    // visible for testing
    void runOneIteration() {
        while (!queue.isEmpty()) {
            Batch batch = queue.poll();
            if (batch == null) continue;
            for (double value : batch.getValues()) {
                processValue(value);
            }
            updateSnapshots();
        }
    }
}
