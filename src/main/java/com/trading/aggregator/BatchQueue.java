package com.trading.aggregator;

import com.trading.model.Batch;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

public class BatchQueue {
    private final ManyToOneConcurrentArrayQueue<Batch> queue;

    public BatchQueue(int capacity) {
        this.queue = new ManyToOneConcurrentArrayQueue<>(capacity);
    }

    public boolean offer(Batch batch) {
        return queue.offer(batch);
    }

    public Batch poll() {
        return queue.poll();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
