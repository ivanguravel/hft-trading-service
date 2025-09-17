package com.trading.dispatcher;

import com.trading.aggregator.SymbolAggregator;
import com.trading.task.TaskRingBuffer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GlobalDispatcher manages a pool of worker threads and a global task queue
 * for symbol aggregators. Instead of running one thread per symbol,
 * we allow a fixed-size pool (e.g. #CPU cores) to process all symbols fairly.
 */
public class GlobalDispatcher {

    private final ExecutorService workerPool;
    private final TaskRingBuffer taskQueue;

    public GlobalDispatcher(int capacityOfItems) {
        int numWorkers = Runtime.getRuntime().availableProcessors();
        this.workerPool = Executors.newFixedThreadPool(numWorkers);
        this.taskQueue = new TaskRingBuffer(capacityOfItems);
        startWorkers(numWorkers);
    }

    /**
     * Submit a SymbolAggregator to be processed.
     * If it's already scheduled (inProgress == true), we do nothing.
     */
    public void submit(SymbolAggregator aggregator) {
        if (aggregator.markInProgress()) {
            taskQueue.offer(aggregator);
        }
    }

    private void startWorkers(int numWorkers) {
        for (int i = 0; i < numWorkers; i++) {
            workerPool.submit(this::workerLoop);
        }
    }

    private void workerLoop() {
        while (taskQueue.isEmpty()) {
            try {
                SymbolAggregator aggregator = taskQueue.take();
                if (aggregator != null) {
                    aggregator.run();
                    aggregator.shutdown();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void shutdown() {
        workerPool.shutdownNow();
    }
}