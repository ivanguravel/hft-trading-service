package com.trading.aggregator;

import com.trading.buffer.RingBuffer;
import com.trading.dispatcher.GlobalDispatcher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class AggregatorManager {
    private final ConcurrentHashMap<String, SymbolAggregator> aggregators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RingBuffer> buffers = new ConcurrentHashMap<>();
    private final Supplier<RingBuffer> ringSupplier;

    private final GlobalDispatcher globalDispatcher;

    public AggregatorManager(Supplier<RingBuffer> ringSupplier, int capacity) {
        this.ringSupplier = ringSupplier;
        this.globalDispatcher = new GlobalDispatcher(capacity);
    }

    public SymbolAggregator getOrCreate(String symbol) {
        SymbolAggregator aggregator = aggregators.computeIfAbsent(symbol, s -> {
            RingBuffer buffer = ringSupplier.get();
            buffers.put(s, buffer);
            return new SymbolAggregator(s, buffer);
        });

        return aggregator;
    }

    public SymbolAggregator getAndPushCalculations(String symbol, List<Double> values) {
        SymbolAggregator aggregator = getOrCreate(symbol);
        aggregator.enqueueBatch(values);
        globalDispatcher.submit(aggregator);
        return aggregator;
    }

    public void shutdownAll() {
        for (SymbolAggregator agg : aggregators.values()) {
            agg.shutdown();
        }
        for (RingBuffer buffer : buffers.values()) {
            buffer.release();
        }
    }
}
