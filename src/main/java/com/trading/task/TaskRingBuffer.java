package com.trading.task;

import com.trading.aggregator.SymbolAggregator;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A simple lock-free ring buffer queue for SymbolAggregator tasks.
 * Only single-consumer is assumed per slot, but multiple workers can poll concurrently.
 */
public class TaskRingBuffer {
    private final ManyToOneConcurrentArrayQueue<SymbolAggregator> buffer;

    public TaskRingBuffer(int capacity) {
        this.buffer = new ManyToOneConcurrentArrayQueue<>(capacity);
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public boolean offer(SymbolAggregator task) {
        return buffer.add(task);
    }

    public SymbolAggregator take()  {
        return buffer.isEmpty() ? null : buffer.poll();
    }
}